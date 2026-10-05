package com.ryotashi29.cnj.runtime.concurrency.cdk;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.rds.AuroraMysqlClusterEngineProps;
import software.amazon.awscdk.services.rds.AuroraMysqlEngineVersion;
import software.amazon.awscdk.services.rds.ClusterInstance;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseCluster;
import software.amazon.awscdk.services.rds.DatabaseClusterEngine;
import software.amazon.awscdk.services.rds.DatabaseProxy;
import software.amazon.awscdk.services.rds.DatabaseProxyOptions;
import software.amazon.awscdk.services.rds.IClusterEngine;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.rds.ParameterGroup;
import software.amazon.awscdk.services.rds.PerformanceInsightRetention;
import software.amazon.awscdk.services.rds.ServerlessV2ClusterInstanceProps;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.constructs.Construct;

/**
 * Aurora MySQL（Serverless v2）と RDS Proxy。仮説 1 の AWS 側の要素。
 *
 * <p>RDS Proxy 経由と直結の両方に到達できるようにしてある。多重化やセッションのピニングは
 * 「プロキシを通すと何が変わるか」でしか語れないため、対照群として直結の経路が必要。
 */
public class DatabaseTier extends Construct {

    /**
     * Serverless v2 の下限。{@code SELECT SLEEP()} は DB の CPU を使わないので、
     * 計測中も容量はほぼ下限に留まる。DB を太らせても測っているものは変わらない。
     */
    private static final Number MIN_ACU = 0.5;

    /** 上限。接続数の増加でメモリ側の要求が上がったときだけ効く。 */
    private static final Number MAX_ACU = 8;

    /**
     * バージョンは aws-cdk-lib の定数で指定する（{@code 8.0.mysql_aurora.3.08.2}）。
     * 文字列で書くと存在しないバージョンでもコンパイルが通り、デプロイまで気づけない。
     */
    private static final AuroraMysqlEngineVersion AURORA_MYSQL_VERSION = AuroraMysqlEngineVersion.VER_3_08_2;

    /**
     * DB 側の接続上限を明示する。Serverless v2 のデフォルト値は容量から自動で決まるため、
     * 「プールサイズが上限を決めている」という仮説が DB 側の上限に汚染されうる。
     * プールは最大でも 25 程度なので、200 を保証しておけば計測の制約にならない。
     */
    private static final String MAX_CONNECTIONS = "200";

    private final DatabaseCluster cluster;
    private final DatabaseProxy proxy;

    public DatabaseTier(Construct scope, String id, IVpc vpc, ISecurityGroup appSecurityGroup) {
        super(scope, id);

        // どちらも外向きは開けっ放しにしない。RDS Proxy が必要なのは Aurora への 3306 だけで、
        // Aurora 自身は外に出る必要がない（isolated サブネットに置いてある）。
        // disableInlineRules の理由は ConcurrencyModelStack の AppSg と同じ（循環参照の回避）。
        // description を ASCII で書く理由も同じ（日本語は EC2 と RDS に弾かれる）
        SecurityGroup proxySecurityGroup = SecurityGroup.Builder.create(this, "ProxySg")
                .vpc(vpc)
                .description("RDS Proxy. Accepts only from the app tasks")
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .build();
        SecurityGroup databaseSecurityGroup = SecurityGroup.Builder.create(this, "DatabaseSg")
                .vpc(vpc)
                .description("Aurora. Accepts from the RDS Proxy and the app tasks")
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .build();

        proxySecurityGroup.addIngressRule(Peer.securityGroupId(appSecurityGroup.getSecurityGroupId()),
                Port.tcp(3306), "App task to RDS Proxy");
        proxySecurityGroup.addEgressRule(Peer.securityGroupId(databaseSecurityGroup.getSecurityGroupId()),
                Port.tcp(3306), "RDS Proxy to Aurora");
        databaseSecurityGroup.addIngressRule(Peer.securityGroupId(proxySecurityGroup.getSecurityGroupId()),
                Port.tcp(3306), "RDS Proxy to Aurora");
        // 直結の経路。RDS Proxy を通したときと通さないときを比べるために開ける。
        // プロキシ経由だけだと「多重化されている」ことを示す基準線が作れない
        databaseSecurityGroup.addIngressRule(Peer.securityGroupId(appSecurityGroup.getSecurityGroupId()),
                Port.tcp(3306), "App task to Aurora (control, bypasses the proxy)");
        appSecurityGroup.addEgressRule(Peer.securityGroupId(proxySecurityGroup.getSecurityGroupId()),
                Port.tcp(3306), "App task to RDS Proxy");
        appSecurityGroup.addEgressRule(Peer.securityGroupId(databaseSecurityGroup.getSecurityGroupId()),
                Port.tcp(3306), "App task to Aurora (control)");

        IClusterEngine engine = DatabaseClusterEngine.auroraMysql(AuroraMysqlClusterEngineProps.builder()
                .version(AURORA_MYSQL_VERSION)
                .build());

        ParameterGroup instanceParameters = ParameterGroup.Builder.create(this, "InstanceParameters")
                .engine(engine)
                .description("Pin max_connections so the DB side is not a measurement variable")
                .parameters(Map.of("max_connections", MAX_CONNECTIONS))
                .build();

        // 監査ログを実際に出させる。cloudwatchLogsExports だけ指定しても、
        // この 2 つを立てないと audit ログには何も出ない（「有効にしたつもり」になる）。
        // CONNECT を採るのは統制のためだけでなく、RDS Proxy の接続多重化を
        // backendConnectionId とは独立にログ側から裏付けられるため
        ParameterGroup clusterParameters = ParameterGroup.Builder.create(this, "ClusterParameters")
                .engine(engine)
                .description("Enable the audit log")
                .parameters(Map.of(
                        "server_audit_logging", "1",
                        "server_audit_events", "CONNECT,QUERY"))
                .build();

        SubnetSelection isolated = SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_ISOLATED)
                .build();

        this.cluster = DatabaseCluster.Builder.create(this, "Aurora")
                .engine(engine)
                .parameterGroup(clusterParameters)
                .writer(ClusterInstance.serverlessV2("writer", ServerlessV2ClusterInstanceProps.builder()
                        .parameterGroup(instanceParameters)
                        .publiclyAccessible(false)
                        // 接続数と待ちを DB 側から独立に見られるようにする。
                        // アプリの申告（/api/diagnostics）だけを根拠にすると、
                        // 「プールが埋まっている」ことを自作の計測器でしか確認できない
                        .enablePerformanceInsights(true)
                        .performanceInsightRetention(PerformanceInsightRetention.DEFAULT)
                        .build()))
                .serverlessV2MinCapacity(MIN_ACU)
                .serverlessV2MaxCapacity(MAX_ACU)
                .vpc(vpc)
                .vpcSubnets(isolated)
                .securityGroups(List.of(databaseSecurityGroup))
                .defaultDatabaseName("vtlab")
                .credentials(Credentials.fromGeneratedSecret("cnjadmin"))
                // 保存時の暗号化。デフォルトでは付かないので明示する
                .storageEncrypted(true)
                // パスワード認証は Secrets Manager 経由で使い続けるが、
                // IAM 認証も使える状態にしておく（有効にするだけでは振る舞いは変わらない）
                .iamAuthentication(true)
                .cloudwatchLogsExports(List.of("audit", "error", "slowquery"))
                .cloudwatchLogsRetention(RetentionDays.ONE_WEEK)
                .backup(software.amazon.awscdk.services.rds.BackupProps.builder()
                        .retention(Duration.days(7))
                        .build())
                // 使い捨ての計測設備なので、スタックを消したら DB も消える設定にしている。
                // 計測データは k6 の結果として手元に残り、DB の中身（SLEEP を呼ぶだけ）に価値はない。
                // 本番系のアカウントでこの設定を流用しないこと
                .deletionProtection(false)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        this.proxy = this.cluster.addProxy("Proxy", DatabaseProxyOptions.builder()
                .dbProxyName("cnj-cm-proxy")
                .secrets(List.of(this.cluster.getSecret()))
                .vpc(vpc)
                .vpcSubnets(isolated)
                .securityGroups(List.of(proxySecurityGroup))
                // TLS を必須にする。アプリ側は DB_SSL_MODE=REQUIRED で繋ぐ。
                // 暗号化の有無は計測の変数ではなく、全条件で同じなので比較には影響しない
                // （ローカルの MySQL で require_secure_transport=ON にして、
                // JDBC と r2dbc の両方が実際に TLS を張っていることは確認済み）
                .requireTls(true)
                .iamAuth(false)
                // セッションのピニングが起きたことをログで確認するために必要。
                // これがないと「多重化されなかった」ことにしか気づけず、理由がわからない
                .debugLogging(true)
                .maxConnectionsPercent(100)
                .borrowTimeout(Duration.seconds(30))
                .idleClientTimeout(Duration.minutes(30))
                .build());
    }

    public String proxyEndpoint() {
        return this.proxy.getEndpoint();
    }

    public String writerEndpoint() {
        return this.cluster.getClusterEndpoint().getHostname();
    }

    public ISecret secret() {
        return this.cluster.getSecret();
    }
}
