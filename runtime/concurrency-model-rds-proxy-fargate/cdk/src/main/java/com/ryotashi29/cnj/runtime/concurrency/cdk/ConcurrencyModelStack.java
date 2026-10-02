package com.ryotashi29.cnj.runtime.concurrency.cdk;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.FlowLogDestination;
import software.amazon.awscdk.services.ec2.FlowLogOptions;
import software.amazon.awscdk.services.ec2.FlowLogTrafficType;
import software.amazon.awscdk.services.ec2.IpAddresses;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * 計測環境の全体。
 *
 * <p>スタックの出力だけを見れば {@code cdk/scripts/measure-aws.sh} が計測を回せる状態にしてある。
 * 計測スクリプトがリソースを名前で決め打ちすると、スタックを作り直したときに
 * 「古い環境を測っていた」という気づけない事故になるため。
 */
public class ConcurrencyModelStack extends Stack {

    /**
     * タスクを public サブネットにパブリック IP 付きで置くか。既定は置かない。
     *
     * <p>置かない方が NAT ゲートウェイのぶん高いが（1 台で $0.06/h 前後）、
     * 「Fargate タスクにパブリック IP が付いている」はセキュリティ統制がまず指摘する構成で、
     * 「セキュリティグループで閉じているから到達できない」という説明は自動チェックには通らない。
     * 計測結果は経路に依存しない（アプリと k6 の通信も DB への通信も VPC 内で完結する）ので、
     * 指摘されない側を既定にしている。安く済ませたいときだけ
     * {@code cdk deploy -c publicTasks=true} で戻す。
     */
    private static final String PUBLIC_TASKS_CONTEXT = "publicTasks";

    public ConcurrencyModelStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        boolean publicTasks = "true".equals(String.valueOf(this.getNode().tryGetContext(PUBLIC_TASKS_CONTEXT)));

        List<SubnetConfiguration> subnets = new ArrayList<>();
        subnets.add(SubnetConfiguration.builder()
                .name("task")
                .subnetType(publicTasks ? SubnetType.PUBLIC : SubnetType.PRIVATE_WITH_EGRESS)
                .cidrMask(24)
                .build());
        if (!publicTasks) {
            // NAT ゲートウェイの置き場所。タスクはここには入らない
            subnets.add(SubnetConfiguration.builder()
                    .name("nat")
                    .subnetType(SubnetType.PUBLIC)
                    .cidrMask(24)
                    .build());
        }
        // DB はどちらの構成でも外向きの経路を持たない
        subnets.add(SubnetConfiguration.builder()
                .name("db")
                .subnetType(SubnetType.PRIVATE_ISOLATED)
                .cidrMask(24)
                .build());

        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .ipAddresses(IpAddresses.cidr("10.20.0.0/16"))
                .maxAzs(2)
                // AZ ごとに置くと 2 台ぶん課金される。計測環境なので片方の AZ が落ちたら作り直す
                .natGateways(publicTasks ? 0 : 1)
                .subnetConfiguration(subnets)
                .build();

        // フローログ。VPC の通信を後から検証できないと、統制側から見て
        // 「何が通ったかわからないネットワーク」になる
        LogGroup flowLogs = LogGroup.Builder.create(this, "FlowLogs")
                .logGroupName("/cnj/concurrency-model/vpc-flow-logs")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        vpc.addFlowLog("FlowLog", FlowLogOptions.builder()
                .destination(FlowLogDestination.toCloudWatchLogs(flowLogs))
                .trafficType(FlowLogTrafficType.ALL)
                .build());

        // 外向きを開けっ放しにしない。タスクが本当に必要なのは
        // ECR / S3 / CloudWatch Logs / Secrets Manager への 443 と、名前解決の 53 だけ。
        //
        // disableInlineRules は必須。CDK は既定でルールをセキュリティグループ本体の
        // プロパティに埋め込むため、A の外向きが B を指し B の受け口が A を指すと
        // CloudFormation のリソースとして循環参照になり、変更セットの作成で落ちる
        // （Circular dependency between resources）。別リソースに切り出せば解消する。
        //
        // description は ASCII で書く。EC2 も RDS も説明文に許す文字を制限していて、
        // 日本語を入れると作成時に弾かれる（RDS は
        // "Description must not contain non-printable control characters" で落ちる）。
        // 意図は日本語のコメントと Javadoc 側に残す
        SecurityGroup appSecurityGroup = SecurityGroup.Builder.create(this, "AppSg")
                .vpc(vpc)
                .description("App tasks under measurement")
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .build();
        SecurityGroup loadSecurityGroup = SecurityGroup.Builder.create(this, "LoadSg")
                .vpc(vpc)
                .description("k6 load generator tasks")
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .build();

        for (SecurityGroup taskSecurityGroup : List.of(appSecurityGroup, loadSecurityGroup)) {
            taskSecurityGroup.addEgressRule(Peer.anyIpv4(), Port.tcp(443),
                    "ECR / S3 / CloudWatch Logs / Secrets Manager");
            taskSecurityGroup.addEgressRule(Peer.ipv4(vpc.getVpcCidrBlock()), Port.udp(53), "DNS within the VPC");
            taskSecurityGroup.addEgressRule(Peer.ipv4(vpc.getVpcCidrBlock()), Port.tcp(53), "DNS within the VPC");
        }
        loadSecurityGroup.addEgressRule(Peer.securityGroupId(appSecurityGroup.getSecurityGroupId()),
                Port.tcp(8080), "k6 task to app task");

        // 8080 を開けるのは k6 のタスクからだけ。負荷を受ける経路は VPC 内に 1 本しかない
        appSecurityGroup.addIngressRule(Peer.securityGroupId(loadSecurityGroup.getSecurityGroupId()),
                Port.tcp(8080), "k6 task to app task");

        DatabaseTier database = new DatabaseTier(this, "Database", vpc, appSecurityGroup);
        MeasurementTier measurement = new MeasurementTier(this, "Measurement", vpc, database);

        String taskSubnetIds = String.join(",", vpc.selectSubnets(SubnetSelection.builder()
                .subnetType(publicTasks ? SubnetType.PUBLIC : SubnetType.PRIVATE_WITH_EGRESS)
                .build()).getSubnetIds());

        output("ClusterName", measurement.clusterName());
        output("TaskSubnetIds", taskSubnetIds);
        // RunTask の awsvpcConfiguration にそのまま渡す。スクリプト側で構成を推測させない
        output("TaskAssignPublicIp", publicTasks ? "ENABLED" : "DISABLED");
        output("AppSecurityGroupId", appSecurityGroup.getSecurityGroupId());
        output("LoadSecurityGroupId", loadSecurityGroup.getSecurityGroupId());
        output("ProxyEndpoint", database.proxyEndpoint());
        output("WriterEndpoint", database.writerEndpoint());
        output("DbSecretArn", database.secret().getSecretArn());
        output("AppTaskFamilies", String.join(",", measurement.appTaskFamilies()));
        output("LoadTaskFamily", measurement.loadTaskFamily());
        output("AppLogGroup", measurement.appLogGroupName());
        output("LoadLogGroup", measurement.loadLogGroupName());
    }

    private void output(String key, String value) {
        CfnOutput.Builder.create(this, key).value(value).build();
    }
}
