# Redis 고가용성 구성

---

## 1. Master/Slave Replication

Master 노드가 데이터를 저장하고 Slave 노드가 Master의 데이터를 복제하여 백업 역할을 한다.

- 주로 **고가용성(HA)** 을 위해 사용
- Redis Sentinel을 추가하면 자동 장애 조치 지원
- 하나의 Master에 다수의 Slave 연결 가능
- Master는 **Read-Write**, Slave는 **Read-Only** 모드로 동작
- Master ↔ Slave 간 Replication은 **비동기(Async)** 방식

### 동작 방식

1. 데이터 변경 시 변경 내용을 **backlog**에 기록
2. Slave가 Master에 접속하여 backlog 기반으로 Replication 수행
3. 비동기 특성상 Master에 저장된 데이터가 Slave에 **잠깐 동안 반영되지 않을 수 있음**

> 예) npm 버전 업데이트 시 검색창 버전과 라이브러리 페이지 버전이 일시적으로 다르게 보이는 현상과 동일

### Master 장애 시 처리

- Slave는 Master에게 주기적으로 Connection을 요청하며 복구될 때까지 대기
- Master 복구 시 Slave는 Replication을 수행하여 동기화
- Master 복구가 불가능한 경우:
    1. Redis 관리자가 Slave 중 하나를 수동으로 Master로 승격
    2. 나머지 Slave들을 새로운 Master로부터 Replication하도록 설정
    3. 기존 죽었던 Master는 새로운 Master의 Slave로 설정

---

## 2. Sentinel

### 문제점

Master가 죽을 경우 Slave를 통해 **읽기는 가능하지만 쓰기는 불가능**하다. Master의 DownTime은 Redis Cluster의 가용성을 떨어뜨린다.

### Sentinel이란

- Master 장애를 자동으로 감지
- 장애 발생 시 Slave 중 하나를 자동으로 Master로 승격
- 기존 Master는 Slave로 강등
- Redis 관리자 없이 자동 동작 → Master DownTime 최소화 → **HA 달성**

### 설정 예시

**sentinel.conf**

```
sentinel monitor mymaster 127.0.0.1 6379 2
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 60000
sentinel parallel-syncs mymaster 1
```

**application.yml**

```yaml
spring:
  redis:
    sentinel:
      master: mymaster
      nodes: 192.168.1.10:26379,192.168.1.11:26379,192.168.1.12:26379
```

### Split-brain 방지 방법

**1. Sentinel 홀수 개 배치 (Quorum 투표)** — 일반적인 방법

- Sentinel을 3개, 5개 등 홀수 개로 배치하여 과반수 확보
- 투표로 가장 많은 Sentinel이 인식한 노드를 Master로 지정

**2. STONITH**

- 문제가 있는 노드를 강제로 종료하여 중복 Master 방지
- 고가용성 클러스터 환경에서 주로 사용

**3. Network Partition Detection**

- 클러스터 네트워크 상태를 주기적으로 체크
- Master 승격 전 네트워크 상태를 검증

---

## 3. HAProxy

Master-Slave 구성 시 클라이언트는 각각의 IP/Port를 직접 알고 접근해야 한다. 하지만 **Master가 교체될 때마다 모든 클라이언트 설정을 변경하는 것은 현실적으로 어렵다.**

HAProxy는 클라이언트에게 Redis Master/Slave에 일정하게 접근할 수 있는 **단일 Endpoint**를 제공한다.

- `tcp-check`를 이용해 각 노드 상태를 주기적으로 파악
- 상태에 따라 **동적으로 Routing Rule 설정**

---

## 4. Redis Cluster

여러 개의 Redis 노드를 사용하여 데이터를 자동으로 **Replication + 샤딩(분산 저장)** 하는 방식

### 특징

- **수평 확장**: 데이터를 여러 Master 노드에 분산 저장
- **고가용성**: 특정 노드 장애 시 자동 복구(Failover)
- **자동 페일오버**: Master 죽으면 Slave가 자동으로 Master로 승격
- **Split-brain 방지**: 홀수 개 구성으로 잘못된 Master 승격 방지
- 각 Redis 노드끼리 직접 연결하여 **Gossip Protocol**로 상태 정보 교환
- Gossip Protocol은 클라이언트 포트보다 높은 번호 사용 (기본: 6379 기준 +10000 = 16379)
- **Multi-master, Multi-slave 구조**

### 중요 동작

- Slave에게 쓰기 요청을 보내면 Slave는 해당 요청을 처리할 수 있는 **Master 정보를 클라이언트에게 전달** (Request Redirection)
- 따라서 Redis Cluster 사용 시 **Cluster를 지원하는 라이브러리 필수** (Spring Data Redis 등)
- Master ↔ Slave 간 Replication은 **비동기** → Master 다운 시 데이터 정합성이 깨질 수 있음
- 데이터 충돌 발생 시 **나중에 Master가 된 데이터를 기준으로 정합성 맞춤**

---

## 5. 비교 요약

| | Master/Slave | Sentinel | Cluster |
| --- | --- | --- | --- |
| 목적 | 복제 + 읽기 분산 | 자동 장애 조치 | 수평 확장 + 고가용성 |
| 자동 페일오버 | X (수동) | O | O |
| 데이터 분산 | X | X | O (샤딩) |
| 구성 복잡도 | 낮음 | 중간 | 높음 |
| 권장 규모 | 소규모 | 중규모 | 대규모 |
