# TS-5. Redis TTL 만료 — 서버 재시작 시 진행 중 경매 데이터 소실

|  |  |
| --- | --- |
| **상황** | anti-sniping 로직에서 입찰마다 TTL을 60초로 갱신하는데, 서버 재시작 또는 Redis 재시작 시 TTL이 남아있는 경매 Hash와 ZSet 데이터가 전부 소실됨. 재시작 후 입찰 시도 시 `AUCTION_NOT_FOUND` 에러 발생. |
| **문제** | Redis 기본 설정은 인메모리만 사용하므로 프로세스 종료 시 모든 데이터가 유실됨. 진행 중인 경매 Hash(`auction:{id}:info`)와 종료 스케줄용 ZSet(`auction:endings`)이 사라져 경매 종료 이벤트도 발생하지 않음. |
| **시도한 방법들** | Sentinel(Master-Slave 자동 페일오버) 도입 검토. Sentinel은 고가용성(HA) 목적으로 노드 장애 시 자동 승격을 제공하지만, 영속성 자체를 보장하지 않음. Master 노드가 재시작되면 동일하게 데이터 유실. 또한 단일 Docker Compose 환경에서 Sentinel 3대 + Redis 노드 구성은 운영 복잡도 대비 실효성 없어 채택하지 않음. |
| **행동** | RDB + AOF 하이브리드 방식(`aof-use-rdb-preamble yes`) 채택. RDB는 스냅샷으로 빠른 복구를 제공하고, AOF는 매 쓰기를 로그로 기록해 RDB 스냅샷 이후 변경분까지 보존. `docker-compose.yml` Redis 커맨드에 `--appendonly yes --aof-use-rdb-preamble yes --save 300 10` 추가. |
| **결과** | 재시작 후 AOF 파일 기반으로 데이터 복구. 진행 중 경매 Hash 및 ZSet 정상 복원. TTL은 재기록되지 않으므로 재시작 후 잔여 TTL로 운영 지속. |
