### AWS 上の計測（RSS はアプリのコンテナが /proc から 1 秒ごとに採取）

| 条件 | サイズ | プール | モード | DB 経路 | prep | ピニング | 起動直後 MB | ピーク MB | 結果 |
|---|---|---|---|---|---|---|---|---|---|
| mvc-platform | cpu1024 | 10 | nodb | proxy | false | n/a | 308 | 566 | aws-mvc-platform-cpu1024-pool10-hold200-scenario-nodb.log |
| mvc-platform | cpu1024 | 10 | db | proxy | false | n/a | 308 | 584 | aws-mvc-platform-cpu1024-pool10-hold200-scenario-db.log |
| mvc-platform | cpu1024 | 10 | bounded | proxy | false | n/a | 308 | 589 | aws-mvc-platform-cpu1024-pool10-hold200-scenario-bounded.log |
| mvc-virtual | cpu1024 | 10 | nodb | proxy | false | n/a | 310 | 700 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-nodb.log |
| mvc-virtual | cpu1024 | 10 | db | proxy | false | n/a | 310 | 710 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-db.log |
| mvc-virtual | cpu1024 | 10 | bounded | proxy | false | n/a | 310 | 711 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-bounded.log |
| webflux-r2dbc | cpu1024 | 10 | nodb | proxy | false | n/a | 344 | 552 | aws-webflux-r2dbc-cpu1024-pool10-hold200-scenario-nodb.log |
| webflux-r2dbc | cpu1024 | 10 | db | proxy | false | n/a | 344 | 567 | aws-webflux-r2dbc-cpu1024-pool10-hold200-scenario-db.log |
| webflux-r2dbc | cpu1024 | 10 | bounded | proxy | false | n/a | 344 | 572 | aws-webflux-r2dbc-cpu1024-pool10-hold200-scenario-bounded.log |
| webflux-blocking | cpu1024 | 10 | blocking | proxy | false | n/a | 345 | 524 | aws-webflux-blocking-cpu1024-pool10-hold200-scenario-blocking.log |
| webflux-blocking | cpu256 | 10 | blocking | proxy | false | n/a | 354 | 440 | aws-webflux-blocking-cpu256-pool10-hold200-crosstalk-blocking.log |
| webflux-r2dbc | cpu256 | 10 | db | proxy | false | n/a | 323 | 426 | aws-webflux-r2dbc-cpu256-pool10-hold200-crosstalk-db.log |
| mvc-virtual-jdk21 | cpu256 | 10 | pinned | proxy | false | n/a | 272 | 298 | aws-mvc-virtual-jdk21-cpu256-pool10-hold200-crosstalk-pinned.log |
| mvc-virtual | cpu256 | 10 | pinned | proxy | false | n/a | 324 | 353 | aws-mvc-virtual-cpu256-pool10-hold200-crosstalk-pinned.log |
### AWS 上の計測（RSS はアプリのコンテナが /proc から 1 秒ごとに採取）

| 条件 | サイズ | プール | モード | DB 経路 | prep | Proxy ピニング | VT ピニング | 最大 lag ms | 起動直後 MB | ピーク MB | 結果 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| mvc-platform | cpu1024 | 10 | nodb | proxy | false | n/a | 0 件 / 最長 0 ms | 2 | 309 | 584 | aws-mvc-platform-cpu1024-pool10-hold200-scenario-nodb.log |
| mvc-platform | cpu1024 | 10 | db | proxy | false | n/a | 0 件 / 最長 0 ms | 0 | 309 | 601 | aws-mvc-platform-cpu1024-pool10-hold200-scenario-db.log |
| mvc-platform | cpu1024 | 10 | bounded | proxy | false | n/a | 0 件 / 最長 0 ms | 0 | 309 | 605 | aws-mvc-platform-cpu1024-pool10-hold200-scenario-bounded.log |
| mvc-virtual | cpu1024 | 10 | nodb | proxy | false | n/a | 0 件 / 最長 0 ms | 249 | 309 | 701 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-nodb.log |
| mvc-virtual | cpu1024 | 10 | db | proxy | false | n/a | 0 件 / 最長 0 ms | 1008 | 309 | 721 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-db.log |
| mvc-virtual | cpu1024 | 10 | bounded | proxy | false | n/a | 0 件 / 最長 0 ms | 297 | 309 | 722 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-bounded.log |
| webflux-r2dbc | cpu1024 | 10 | nodb | proxy | false | n/a | n/a | 0 | 349 | 554 | aws-webflux-r2dbc-cpu1024-pool10-hold200-scenario-nodb.log |
| webflux-r2dbc | cpu1024 | 10 | db | proxy | false | n/a | n/a | 0 | 349 | 571 | aws-webflux-r2dbc-cpu1024-pool10-hold200-scenario-db.log |
| webflux-r2dbc | cpu1024 | 10 | bounded | proxy | false | n/a | n/a | 53 | 349 | 576 | aws-webflux-r2dbc-cpu1024-pool10-hold200-scenario-bounded.log |
| webflux-blocking | cpu1024 | 10 | blocking | proxy | false | n/a | n/a | 22749 | 347 | 524 | aws-webflux-blocking-cpu1024-pool10-hold200-scenario-blocking.log |
| webflux-blocking-isolated | cpu256 | 10 | blocking-isolated | proxy | false | n/a | n/a | 81 | 358 | 524 | aws-webflux-blocking-isolated-cpu256-pool10-hold200-crosstalk-blocking-isolated.log |
| mvc-virtual-jdk21-lock | cpu256 | 10 | pinned-lock | proxy | false | n/a | 0 件 / 最長 0 ms | 0 | 265 | 418 | aws-mvc-virtual-jdk21-lock-cpu256-pool10-hold200-crosstalk-pinned-lock.log |
| mvc-virtual-jdk21 | cpu256 | 10 | pinned | proxy | false | n/a | 130 件 / 最長 1001 ms | 29750 | 285 | 302 | aws-mvc-virtual-jdk21-cpu256-pool10-hold200-crosstalk-pinned.log |
| mvc-virtual | cpu256 | 10 | pinned | proxy | false | n/a | 0 件 / 最長 0 ms | 0 | 305 | 399 | aws-mvc-virtual-cpu256-pool10-hold200-crosstalk-pinned.log |
| webflux-blocking | cpu256 | 10 | blocking | proxy | false | n/a | n/a | 7000 | 359 | 428 | aws-webflux-blocking-cpu256-pool10-hold200-crosstalk-blocking.log |
| mvc-platform | cpu1024 | 5 | db | proxy | false | n/a | 0 件 / 最長 0 ms | 0 | 313 | 585 | aws-mvc-platform-cpu1024-pool5-hold200-scenario-db.log |
| mvc-platform | cpu1024 | 10 | db | proxy | false | n/a | 0 件 / 最長 0 ms | 0 | 314 | 583 | aws-mvc-platform-cpu1024-pool10-hold200-scenario-db.log |
| mvc-platform | cpu1024 | 25 | db | proxy | false | n/a | 0 件 / 最長 0 ms | 0 | 327 | 584 | aws-mvc-platform-cpu1024-pool25-hold200-scenario-db.log |
| mvc-virtual | cpu1024 | 5 | db | proxy | false | n/a | 0 件 / 最長 0 ms | 749 | 318 | 715 | aws-mvc-virtual-cpu1024-pool5-hold200-scenario-db.log |
| mvc-virtual | cpu1024 | 10 | db | proxy | false | n/a | 0 件 / 最長 0 ms | 999 | 319 | 718 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-db.log |
| mvc-virtual | cpu1024 | 25 | db | proxy | false | n/a | 0 件 / 最長 0 ms | 1000 | 320 | 712 | aws-mvc-virtual-cpu1024-pool25-hold200-scenario-db.log |
| webflux-r2dbc | cpu1024 | 5 | db | proxy | false | n/a | n/a | 0 | 345 | 569 | aws-webflux-r2dbc-cpu1024-pool5-hold200-scenario-db.log |
| webflux-r2dbc | cpu1024 | 10 | db | proxy | false | n/a | n/a | 8 | 351 | 547 | aws-webflux-r2dbc-cpu1024-pool10-hold200-scenario-db.log |
| webflux-r2dbc | cpu1024 | 25 | db | proxy | false | n/a | n/a | 2 | 353 | 566 | aws-webflux-r2dbc-cpu1024-pool25-hold200-scenario-db.log |
| mvc-virtual | cpu1024 | 10 | db | direct | false | - | 0 件 / 最長 0 ms | 750 | 326 | 713 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-db.log |
| mvc-virtual | cpu1024 | 10 | db | proxy | true | 6 | 1 件 / 最長 38 ms | 1000 | 318 | 719 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-db-prepon.log |
| mvc-virtual | cpu1024 | 25 | db | proxy | false | 0 | 0 件 / 最長 0 ms | 499 | 318 | 720 | aws-mvc-virtual-cpu1024-pool25-hold200-scenario-db.log |
| mvc-virtual | cpu1024 | 25 | db | proxy | false | 0 | 0 件 / 最長 0 ms | 500 | 307 | 724 | aws-mvc-virtual-cpu1024-pool25-hold200-scenario-db-proxy-20260926-0214.log |
| mvc-virtual | cpu1024 | 25 | db | proxy | false | 0 | 0 件 / 最長 0 ms | 750 | 330 | 719 | aws-mvc-virtual-cpu1024-pool25-hold200-scenario-db-proxy-20260926-0247.log |
| mvc-platform | cpu1024 | 5 | tx-hold | proxy | false | 0 | 0 件 / 最長 0 ms | 1 | 311 | 594 | aws-mvc-platform-cpu1024-pool5-hold200-scenario-tx-hold-proxy-20260926-2042.log |
| mvc-platform | cpu1024 | 10 | tx-hold | proxy | false | 0 | 0 件 / 最長 0 ms | 0 | 329 | 602 | aws-mvc-platform-cpu1024-pool10-hold200-scenario-tx-hold-proxy-20260926-2042.log |
| mvc-platform | cpu1024 | 25 | tx-hold | proxy | false | 0 | 0 件 / 最長 0 ms | 0 | 323 | 592 | aws-mvc-platform-cpu1024-pool25-hold200-scenario-tx-hold-proxy-20260926-2042.log |
| mvc-virtual | cpu1024 | 5 | tx-hold | proxy | false | 0 | 0 件 / 最長 0 ms | 750 | 313 | 653 | aws-mvc-virtual-cpu1024-pool5-hold200-scenario-tx-hold-proxy-20260926-2042.log |
| mvc-virtual | cpu1024 | 10 | tx-hold | proxy | false | 0 | 0 件 / 最長 0 ms | 751 | 315 | 670 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-tx-hold-proxy-20260926-2042.log |
| mvc-virtual | cpu1024 | 25 | tx-hold | proxy | false | 0 | 0 件 / 最長 0 ms | 249 | 318 | 681 | aws-mvc-virtual-cpu1024-pool25-hold200-scenario-tx-hold-proxy-20260927-0225.log |
| webflux-r2dbc | cpu1024 | 5 | tx-hold | proxy | false | 0 | n/a | 0 | 342 | 568 | aws-webflux-r2dbc-cpu1024-pool5-hold200-scenario-tx-hold-proxy-20260927-0232.log |
| webflux-r2dbc | cpu1024 | 10 | tx-hold | proxy | false | 0 | n/a | 0 | 349 | 567 | aws-webflux-r2dbc-cpu1024-pool10-hold200-scenario-tx-hold-proxy-20260927-0232.log |
| webflux-r2dbc | cpu1024 | 25 | tx-hold | proxy | false | 0 | n/a | 1 | 359 | 569 | aws-webflux-r2dbc-cpu1024-pool25-hold200-scenario-tx-hold-proxy-20260927-0232.log |
| mvc-virtual | cpu1024 | 10 | tx-hold | direct | false | - | 0 件 / 最長 0 ms | 999 | 321 | 678 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-tx-hold-direct-20260927-0306.log |
| mvc-virtual | cpu1024 | 10 | tx-hold | proxy | true | 7 | 0 件 / 最長 0 ms | 500 | 320 | 669 | aws-mvc-virtual-cpu1024-pool10-hold200-scenario-tx-hold-prepon-proxy-20260927-0313.log |
