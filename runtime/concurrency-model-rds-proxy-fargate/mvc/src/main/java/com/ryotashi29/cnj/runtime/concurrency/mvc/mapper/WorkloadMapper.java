package com.ryotashi29.cnj.runtime.concurrency.mvc.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkloadMapper {

    /**
     * 指定秒数だけサーバ側で待つ。MySQL の {@code SLEEP()} は CPU を消費せずに接続だけを占有するため、
     * 「DB が重い」と「接続が枯渇している」を切り分けて計測できる。
     */
    Integer sleep(@Param("seconds") double seconds);

    /**
     * MySQL 側から見た接続 ID。RDS Proxy 経由の場合、同じ HikariCP 接続を使っていても
     * 多重化が効いていれば呼び出しごとに変わり、セッションがピニングされると変わらなくなる。
     */
    Long backendConnectionId();
}
