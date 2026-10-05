package com.ryotashi29.cnj.runtime.concurrency.cdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ecr.assets.DockerImageAsset;
import software.amazon.awscdk.services.ecr.assets.Platform;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerDependency;
import software.amazon.awscdk.services.ecs.ContainerDependencyCondition;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.ContainerInsights;
import software.amazon.awscdk.services.ecs.CpuArchitecture;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.ContainerDefinition;
import software.amazon.awscdk.services.ecs.HealthCheck;
import software.amazon.awscdk.services.ecs.LogDrivers;
import software.amazon.awscdk.services.ecs.MountPoint;
import software.amazon.awscdk.services.ecs.OperatingSystemFamily;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.RuntimePlatform;
import software.amazon.awscdk.services.ecs.Secret;
import software.amazon.awscdk.services.ecs.Volume;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.constructs.Construct;

/**
 * 計測を実行する側。ECS Fargate のタスク定義とコンテナイメージ。
 *
 * <p>ECS サービスも ALB も作らない。サービスにすると条件を変えるたびにサービス更新の
 * デプロイサイクルが挟まり、ALB を挟むとロードバランサ側のキューが並行モデルの差に混ざる。
 * 条件ごとに {@code RunTask} で単発のタスクを立て、k6 のタスクからタスクの ENI へ直接叩く。
 *
 * <p>タスクサイズは計測 1-5 の変数なので、サイズごとに別のタスク定義を用意する
 * （タスクサイズはタスク定義のプロパティで、実行時に上書きできない）。
 */
public class MeasurementTier extends Construct {

    private static final String CORRETTO_25 = "public.ecr.aws/amazoncorretto/amazoncorretto:25";
    private static final String CORRETTO_21 = "public.ecr.aws/amazoncorretto/amazoncorretto:21";

    /**
     * ビルドするイメージ。JDK は主軸の 25 と、{@code pinned} の対照群としての 21。
     *
     * <p>webflux に 21 版がないのは、イベントループのブロックが JDK に依存しないため
     * （docs/design.md の「JDK による違い」の表）。測っても同じ結果になる。
     */
    private static final List<AppVariant> VARIANTS = List.of(
            new AppVariant("mvc-jdk25", "MvcJdk25", "mvc", CORRETTO_25),
            new AppVariant("mvc-jdk21", "MvcJdk21", "mvc", CORRETTO_21),
            new AppVariant("webflux-jdk25", "WebfluxJdk25", "webflux", CORRETTO_25));

    /**
     * 計測 1-5 の変数。0.25 vCPU から 2 vCPU まで。
     *
     * <p>メモリを 2 GB で揃えたいが、2 vCPU は 4 GB 未満を選べないという Fargate の制約がある。
     * そのままだと 3 モデルの RSS 比較にメモリ上限の差が混ざるため、ヒープは
     * {@code JAVA_OPTS} で全サイズ固定にしてある（下の {@code JAVA_OPTS} を参照）。
     */
    private static final List<TaskSize> SIZES = List.of(
            new TaskSize("cpu256", 256, 2048),
            new TaskSize("cpu512", 512, 2048),
            new TaskSize("cpu1024", 1024, 2048),
            new TaskSize("cpu2048", 2048, 4096));

    /**
     * ヒープと GC を固定する。タスクサイズを変えると {@code MaxRAMPercentage} 由来のヒープも動くため、
     * 固定しないと「タスクサイズを変えた結果」に「ヒープが変わった結果」が混ざる。
     * 測りたいのは CPU 側（{@code availableProcessors} とキャリアスレッド・イベントループの本数）だけ。
     *
     * <p>GC を明示するのは同じ理由。JVM の ergonomics は「2 CPU 以上かつ 2 GB 以上」で
     * G1 を選ぶため、{@code cpu256}（{@code availableProcessors} が 1）だけ Serial GC になる。
     * 黙って変わると、スループットと RSS の差のうちどこまでがスレッドの本数の話なのかが
     * 分けられなくなる。{@code ActiveProcessorCount} は独立変数なので固定しない。
     */
    private static final String JAVA_OPTS = "-Xms512m -Xmx512m -XX:+UseG1GC";

    /** {@code cdk/docker/app/Dockerfile} の {@code useradd --uid 10001 app} と一致させる。 */
    private static final String APP_UID = "10001";

    /** {@code grafana/k6} のイメージが作る {@code k6} ユーザーの uid。 */
    private static final String K6_UID = "12345";

    private final Cluster cluster;
    private final LogGroup appLogGroup;
    private final LogGroup loadLogGroup;
    private final List<String> appTaskFamilies = new ArrayList<>();
    private final String loadTaskFamily;

    public MeasurementTier(Construct scope, String id, IVpc vpc, DatabaseTier database) {
        super(scope, id);

        this.cluster = Cluster.Builder.create(this, "Ecs")
                .vpc(vpc)
                .clusterName("cnj-cm")
                // コンテナのメモリ使用量を cgroup 側から独立に観測する。アプリのコンテナ内でも
                // /proc から RSS を採るが、2 系統で突き合わせられないと「どちらが正しいか」を判断できない
                .containerInsightsV2(ContainerInsights.ENABLED)
                .build();

        this.appLogGroup = LogGroup.Builder.create(this, "AppLogs")
                .logGroupName("/cnj/concurrency-model/app")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        this.loadLogGroup = LogGroup.Builder.create(this, "LoadLogs")
                .logGroupName("/cnj/concurrency-model/k6")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Path context = validationRoot();

        for (AppVariant variant : VARIANTS) {
            DockerImageAsset image = DockerImageAsset.Builder.create(this, "Image" + variant.constructId())
                    .directory(context.toString())
                    .file("cdk/docker/app/Dockerfile")
                    .buildArgs(Map.of(
                            "MODULE", variant.module(),
                            "RUNTIME_IMAGE", variant.runtimeImage()))
                    .platform(Platform.LINUX_ARM64)
                    .exclude(assetExcludes())
                    .build();

            for (TaskSize size : SIZES) {
                this.appTaskFamilies.add(appTaskDefinition(variant, size, image, database).getFamily());
            }
        }

        this.loadTaskFamily = loadTaskDefinition(context).getFamily();
    }

    /**
     * アプリ 1 条件ぶんのタスク定義。条件（仮想スレッドの有無・プールサイズ・保持時間）は
     * {@code RunTask} の {@code containerOverrides} で渡すので、ここではデフォルト値だけ置く。
     */
    private FargateTaskDefinition appTaskDefinition(AppVariant variant, TaskSize size,
            DockerImageAsset image, DatabaseTier database) {
        String family = "cnj-cm-" + variant.name() + "-" + size.name();
        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder
                .create(this, "Task" + variant.constructId() + size.constructId())
                .family(family)
                .cpu(size.cpu())
                .memoryLimitMiB(size.memoryMiB())
                .runtimePlatform(RuntimePlatform.builder()
                        .cpuArchitecture(CpuArchitecture.ARM64)
                        .operatingSystemFamily(OperatingSystemFamily.LINUX)
                        .build())
                .build();

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("DB_HOST", database.proxyEndpoint());
        environment.put("DB_PORT", "3306");
        environment.put("DB_NAME", "vtlab");
        environment.put("POOL_SIZE", "10");
        environment.put("WORKLOAD_PERMITS", "10");
        environment.put("VIRTUAL_THREAD_ENABLED", "false");
        environment.put("JAVA_OPTS", JAVA_OPTS);
        // RDS Proxy を TLS 必須にしているため。直結（対照群）の Aurora も TLS を受ける
        environment.put("DB_SSL_MODE", "REQUIRED");

        ISecret secret = database.secret();
        ContainerDefinition container = taskDefinition.addContainer("app", ContainerDefinitionOptions.builder()
                .containerName("app")
                .image(ContainerImage.fromDockerImageAsset(image))
                .environment(environment)
                // イメージの USER と同じ uid。タスク定義側にも書いておかないと
                // 「root で動いていないこと」をタスク定義だけ見て確認できない
                .user(APP_UID)
                .readonlyRootFilesystem(true)
                .secrets(Map.of(
                        "DB_USER", Secret.fromSecretsManager(secret, "username"),
                        "DB_PASSWORD", Secret.fromSecretsManager(secret, "password")))
                .portMappings(List.of(PortMapping.builder().containerPort(8080).build()))
                // 起動完了を外から判定するために必要。RunTask したタスクの lastStatus は
                // プロセスが受け付け可能になる前に RUNNING になるため、healthStatus を待つ。
                //
                // retries の上限は 10（ECS が弾く）。0.25 vCPU では Spring Boot の起動に
                // 1 分近くかかるので、猶予は startPeriod 側で取る。
                // 判定までの窓は startPeriod + retries × interval = 60 + 100 = 160 秒
                .healthCheck(HealthCheck.builder()
                        .command(List.of("CMD-SHELL", "curl -fsS http://localhost:8080/actuator/health || exit 1"))
                        .interval(Duration.seconds(10))
                        .timeout(Duration.seconds(5))
                        .retries(10)
                        .startPeriod(Duration.seconds(60))
                        .build())
                .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                        .logGroup(this.appLogGroup)
                        .streamPrefix(family)
                        .build()))
                .build());

        restrictFilesystem(taskDefinition, container, ContainerImage.fromDockerImageAsset(image),
                APP_UID, this.appLogGroup, family);
        return taskDefinition;
    }

    /** k6 を VPC 内から流すタスク。負荷生成と計測対象が CPU を奪い合わないよう別タスクにしている。 */
    private FargateTaskDefinition loadTaskDefinition(Path context) {
        DockerImageAsset image = DockerImageAsset.Builder.create(this, "ImageK6")
                .directory(context.toString())
                .file("cdk/docker/k6/Dockerfile")
                .platform(Platform.LINUX_ARM64)
                .exclude(assetExcludes())
                .build();

        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder.create(this, "TaskK6")
                .family("cnj-cm-k6")
                // 同時 800 VU まで上げるので、負荷側がボトルネックにならないサイズにしておく。
                // ここが足りないと「アプリの上限」ではなく「k6 の上限」を測ってしまう
                .cpu(2048)
                .memoryLimitMiB(4096)
                .runtimePlatform(RuntimePlatform.builder()
                        .cpuArchitecture(CpuArchitecture.ARM64)
                        .operatingSystemFamily(OperatingSystemFamily.LINUX)
                        .build())
                .build();

        ContainerDefinition container = taskDefinition.addContainer("k6", ContainerDefinitionOptions.builder()
                .containerName("k6")
                .image(ContainerImage.fromDockerImageAsset(image))
                .environment(Map.of(
                        "MODE", "db",
                        "HOLD_MS", "200",
                        // handleSummary の書き出し先。結果は stdout にも出るので、
                        // 実体は CloudWatch Logs から回収する
                        "RESULTS_DIR", "/tmp"))
                .user(K6_UID)
                .readonlyRootFilesystem(true)
                .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                        .logGroup(this.loadLogGroup)
                        .streamPrefix("k6")
                        .build()))
                .build());

        restrictFilesystem(taskDefinition, container, ContainerImage.fromDockerImageAsset(image),
                K6_UID, this.loadLogGroup, "k6");
        return taskDefinition;
    }

    /**
     * ルートファイルシステムを読み取り専用にし、書けるのは {@code /tmp} だけにする。
     *
     * <p>{@code /tmp} は消せない。JVM は {@code java.io.tmpdir} に hsperfdata を作り、
     * Tomcat も作業ディレクトリを掘る。k6 は {@code handleSummary} の結果をここへ書く。
     * タスクのエフェメラルストレージに乗る名前だけのボリュームなので、タスクと一緒に消える。
     *
     * <p>ボリュームの所有者を移すために root の初期化コンテナを挟んでいる。Fargate は
     * 名前だけのボリュームを {@code root:root} の {@code 0755} で作るため、非 root の
     * コンテナからは書けない（Tomcat が {@code java.io.tmpdir} に作業ディレクトリを
     * 作れず {@code Unable to create tempDir} で起動に失敗する）。イメージ側で
     * {@code /tmp} のモードを直しても、マウントが覆うので効かない。
     * 本体は {@code SUCCESS} 依存で待つので、chown が終わる前には動き出さない。
     *
     * <p>「読み取り専用ルート FS をやめる」でも直るが、それは Security Hub の
     * {@code ECS.5} が見る項目なので、指摘の出ない側を選んでいる。
     */
    private void restrictFilesystem(FargateTaskDefinition taskDefinition, ContainerDefinition container,
            ContainerImage image, String uid, LogGroup logGroup, String streamPrefix) {
        taskDefinition.addVolume(Volume.builder().name("tmp").build());

        // イメージは本体と同じものを使う。別のイメージにすると pull が 1 回増える。
        // ENTRYPOINT を上書きしているのは、本体のイメージがアプリや k6 を起動する設定に
        // なっているため（command だけでは ENTRYPOINT の引数になってしまう）
        ContainerDefinition initContainer = taskDefinition.addContainer("init-tmp",
                ContainerDefinitionOptions.builder()
                        .containerName("init-tmp")
                        .image(image)
                        .user("0")
                        // 終了して当然のコンテナなので、落ちたことにしない
                        .essential(false)
                        .readonlyRootFilesystem(true)
                        .entryPoint(List.of("/bin/sh", "-c"))
                        // 所有者だけ移して 700 にする。グループは root のまま権限なし
                        .command(List.of("chown " + uid + " /tmp && chmod 700 /tmp"))
                        .logging(LogDrivers.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(logGroup)
                                .streamPrefix(streamPrefix)
                                .build()))
                        .build());

        for (ContainerDefinition each : List.of(initContainer, container)) {
            each.addMountPoints(MountPoint.builder()
                    .containerPath("/tmp")
                    .sourceVolume("tmp")
                    .readOnly(false)
                    .build());
        }

        container.addContainerDependencies(ContainerDependency.builder()
                .container(initContainer)
                .condition(ContainerDependencyCondition.SUCCESS)
                .build());
    }

    /**
     * Docker のビルドコンテキストは検証のルート（{@code common} / {@code mvc} / {@code webflux} /
     * {@code k6} を含む階層）。イメージの中でソースから jar を作るので、ビルド済みの jar を
     * 前提にしない（手元の jar が古いまま計測される事故を防ぐ）。
     */
    private Path validationRoot() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        if (!Files.exists(root.resolve("settings.gradle")) || !Files.isDirectory(root.resolve("k6"))) {
            throw new IllegalStateException(
                    "検証のルートを解決できません: " + root + " （cdk ディレクトリから合成していますか）");
        }
        return root;
    }

    /**
     * ビルド成果物と計測結果を除く。イメージのハッシュが計測のたびに変わるのを防ぐ意味もある。
     *
     * <p>{@code cdk/scripts} と {@code cdk/src} も除く。イメージはどちらも要らないのに、
     * 含めておくと計測スクリプトや CDK のコードを直すたびにハッシュが変わり、
     * 3 イメージの再ビルドとプッシュが走る。{@code cdk/docker} は Dockerfile が要るので残す。
     */
    private static List<String> assetExcludes() {
        return List.of(
                ".git", ".gradle", "*/.gradle", "build", "*/build",
                "cdk/build", "cdk/.gradle", "cdk/cdk.out",
                "cdk/scripts", "cdk/src", "README.md",
                "k6/results", "server");
    }

    public String clusterName() {
        return this.cluster.getClusterName();
    }

    public List<String> appTaskFamilies() {
        return List.copyOf(this.appTaskFamilies);
    }

    public String loadTaskFamily() {
        return this.loadTaskFamily;
    }

    public String appLogGroupName() {
        return this.appLogGroup.getLogGroupName();
    }

    public String loadLogGroupName() {
        return this.loadLogGroup.getLogGroupName();
    }

    /** イメージ 1 種類ぶんの定義。モジュールと JDK の組み合わせだけが違う。 */
    private record AppVariant(String name, String constructId, String module, String runtimeImage) {
    }

    /** タスクサイズ 1 段。{@code memoryMiB} は Fargate が許す組み合わせから選ぶ。 */
    private record TaskSize(String name, int cpu, int memoryMiB) {
        String constructId() {
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }
}
