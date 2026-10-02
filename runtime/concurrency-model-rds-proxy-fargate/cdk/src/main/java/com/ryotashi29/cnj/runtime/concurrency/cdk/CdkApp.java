package com.ryotashi29.cnj.runtime.concurrency.cdk;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * 並行モデル比較の計測環境を 1 スタックで作る。
 *
 * <p>ネットワーク・DB・計測用のタスク定義をスタックに分けていないのは、この環境が
 * 「計測のあいだだけ立てて、終わったら丸ごと消す」使い捨ての設備であるため。
 * スタックを分けると削除順序を気にする必要が出てきて、消し残しによる課金の温床になる。
 */
public final class CdkApp {

    private CdkApp() {
    }

    public static void main(String[] args) {
        App app = new App();

        new ConcurrencyModelStack(app, "cnj-concurrency-model", StackProps.builder()
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv("CDK_DEFAULT_REGION"))
                        .build())
                .description("Java 並行モデル比較の計測環境（Aurora MySQL + RDS Proxy + ECS Fargate）")
                .build());

        app.synth();
    }
}
