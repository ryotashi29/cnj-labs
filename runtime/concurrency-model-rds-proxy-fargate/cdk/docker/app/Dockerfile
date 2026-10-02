# 計測対象のアプリ。ビルドコンテキストは検証のルート（common / mvc / webflux / k6 を含む階層）。
#
# イメージの中でソースから jar を作るのは、手元の build/libs に残った古い jar を
# 黙って計測してしまう事故を防ぐため（プロジェクト改名時に実際に起きた）。
#
# ビルド段は JDK 21。Gradle 9.7.1 自体は JDK 25 でも動くが、toolchain が 21 なので
# コンパイルに JDK 21 が必要で、この段には JDK が 1 つしか入っていない。
# 実行段は RUNTIME_IMAGE で切り替える（主軸は Corretto 25、pinned の対照群が 21）。
# toolchain 21 のバイトコードは 25 でそのまま動くので、この組み合わせが成立する。
ARG RUNTIME_IMAGE=public.ecr.aws/amazoncorretto/amazoncorretto:25

FROM public.ecr.aws/amazoncorretto/amazoncorretto:21 AS build
# gradlew（wrapper のシェルスクリプト）が xargs を使う。Corretto のベースイメージには
# findutils が入っていないため、入れないと「xargs is not available」で落ちる
RUN dnf install -y findutils && dnf clean all
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY common ./common
COPY mvc ./mvc
COPY webflux ./webflux
ARG MODULE=mvc
# bootJar だけを叩く。build にすると *-plain.jar も出て「どちらの jar を実行したか」が曖昧になる。
# jar が 1 つに定まらなければ落とす（sweep.sh の find_jar と同じ考え方）
RUN set -eu; \
    ./gradlew --no-daemon --console=plain -q ":${MODULE}:bootJar"; \
    jar=$(find "${MODULE}/build/libs" -name '*.jar'); \
    if [ "$(printf '%s\n' "$jar" | wc -l)" -ne 1 ]; then \
        echo "jar を一意に特定できません: $jar" >&2; exit 1; \
    fi; \
    cp "$jar" /app.jar

FROM ${RUNTIME_IMAGE}
# curl はコンテナのヘルスチェックに使う（起動完了を ECS 側から判定するため）。
# shadow-utils は useradd のため。ベースイメージに入っている保証がないので明示して入れる
RUN dnf install -y --allowerasing curl shadow-utils \
    && dnf clean all \
    && useradd --system --uid 10001 --create-home app
WORKDIR /opt/app
COPY --from=build /app.jar ./app.jar
COPY cdk/docker/app/entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh && chown -R app:app /opt/app
USER app
EXPOSE 8080
ENTRYPOINT ["/opt/app/entrypoint.sh"]
