# 負荷生成側。ビルドコンテキストは検証のルートで、k6/ をそのまま持ち込む。
#
# ローカルと AWS で同じシナリオを使う。負荷の形が違うと、ローカルで確認した計測器の
# 挙動が AWS の結果を説明できなくなる。
FROM grafana/k6:0.54.0

USER root
COPY k6 /scripts/k6
COPY cdk/docker/k6/run.sh /scripts/run.sh
RUN chmod +x /scripts/run.sh
USER k6

# k6 のイメージは k6 自身が ENTRYPOINT なので、diagnostics の記録を挟むために差し替える
ENTRYPOINT ["/scripts/run.sh"]
