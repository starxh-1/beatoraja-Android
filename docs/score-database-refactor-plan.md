# Score 数据库重构方案

## 现状分析

### 问题一：双轨制 SQLite 访问导致锁竞争（严重）

项目同时使用两套完全不同的 SQLite 访问层：

| 数据库 | 访问方式 | 文件 |
|--------|----------|------|
| Song 数据库 | Android 原生 `SQLiteDatabase` / `SQLiteOpenHelper` | `song.db` |
| Score 数据库 | JDBC (SQLDroid driver) + Apache DbUtils `QueryRunner` | `score.db` |
| ScoreLog 数据库 | JDBC (SQLDroid driver) + `QueryRunner` | `scorelog.db` |
| ScoreDataLog 数据库 | JDBC (SQLDroid driver) + `QueryRunner` | `scoredatalog.db` |

两者的底层实现都最终调用 `android.database.sqlite.SQLiteDatabase.openDatabase()`，但走的是**完全独立的连接管理路径**：

- **Song DB** (`AndroidSQLiteSongDatabaseAccessor`): 使用 `SQLiteOpenHelper` 管理单例长连接，`onConfigure()` 中开启 WAL 模式，`onOpen()` 设置 `busy_timeout = 15000`
- **Score DB** (`ScoreDatabaseAccessor` 等): 通过 `DatabaseUtils.getDataSource()` 走 `SQLDroidDriver` → `DriverManager.getConnection()`，在 `DatabaseUtils` 中做了共享连接缓存、`PRAGMA busy_timeout = 2000`、`PRAGMA journal_mode = WAL` 等

**根本问题**: 两套连接管理独立运作。当 `AndroidSQLiteSongDatabaseAccessor` 的 `SQLiteOpenHelper` 已经持有某个 SQLite 文件的写锁时，JDBC 侧的 `SQLDroidDriver` 再尝试 open 同一个或另一个 SQLite 文件，底层 Android SQLite 会检测到文件级别的锁冲突，抛出 `SQLiteDatabaseLockedException`。

这在 Result 界面尤为致命：
- 渲染线程通过 `ScoreDatabaseAccessor.getScoreData()` 读 score.db (JDBC 路径)
- 写分线程通过 `PlayDataAccessor.writeScoreData()` 写 score.db/scoredatalog.db/scorelog.db (JDBC 路径)
- UI 线程可能同时通过 `SongDatabaseAccessor.getSongDatas()` 读 song.db (Android 原生路径)

三者在底层文件系统层面产生锁竞争，导致 UI 卡死或数据写入超时。

`ScoreDatabaseAccessor` 已有 `ReentrantLock` 和 500ms 读超时作为缓解措施，注释中甚至残留了中文的 DEBUG PROBE 代码（ResultFreezeDiagnostics 相关的被注释代码），说明这个问题已经困扰项目很久。

### 问题二：三个 Score DB 文件冗余

当前在 `player/<playername>/` 目录下会生成三个独立文件：

| 文件 | 访问器 | 表结构 |
|------|--------|--------|
| `score.db` | `ScoreDatabaseAccessor` | `info`(3列), `player`(15列), `score`(24列) |
| `scoredatalog.db` | `ScoreDataLogDatabaseAccessor` | `scoredatalog`(23列，与score表schema完全相同) |
| `scorelog.db` | `ScoreLogDatabaseAccessor` | `scorelog`(9列) |

各表的用途：
- **score** — 每张谱面的最佳成绩（按 sha256+mode 去重）
- **scoredatalog** — 每次游玩的完整成绩历史（schema 与 score 完全相同）
- **scorelog** — 成绩变化日志（记录 clear/score/combo/minbp 的新旧值变更）

**问题**：
1. 三个文件各自有独立的 schema 验证、连接管理、WAL 文件、shm 文件，文件句柄消耗是 3 倍
2. `score` 表和 `scoredatalog` 表有完全相同的列结构，分开纯粹是组织习惯
3. 跨表查询不可能（例如：统计某玩家某谱面的历史成绩趋势），因为 SQLite 的 ATTACH DATABASE 在 Android 上不可靠

### 问题三：SQL 注入风险

`ScoreDatabaseAccessor` 中的多处查询使用了字符串拼接而非参数化查询：

```java
// ScoreDatabaseAccessor.java:140 - SQL注入风险
qr.query("SELECT * FROM score WHERE sha256 = '" + hash + "' AND mode = " + mode, ...)

// ScoreDatabaseAccessor.java:260 - SQL注入风险
qr.update(con, "UPDATE score SET " + vs + "WHERE sha256 = '" + hash + "'")
```

虽然 sha256 通常由内部生成，但 `setScoreData(Map)` 方法接收外部 Map 的 key 直接拼接到 SQL 中。

---

## 重构方案

### 目标

1. **统一访问层**: 将 Score 系列 DB 全面迁移到 Android 原生 `SQLiteDatabase` API
2. **合并为单文件**: score.db 包含全部 4 张表（info, player, score, scorelog, scoredatalog）
3. **消除锁竞争**: 所有 SQLite 访问走同一套连接管理 (SQLiteOpenHelper)
4. **修复 SQL 注入**: 所有查询改用参数化

### 架构变更

```
Before:
  Song DB (Android native)        Score DB (JDBC/SQLDroid)
  └── SQLiteOpenHelper            ├── DatabaseUtils (shared conn pool)
       (song.db)                  │   ├── ScoreDatabaseAccessor → score.db
                                  │   ├── ScoreLogDatabaseAccessor → scorelog.db
                                  │   └── ScoreDataLogDatabaseAccessor → scoredatalog.db
                                  └── ReentrantLock (手动加锁, 500ms 超时)
  ↑ 锁竞争 ↑

After:
  Song DB (Android native)        Score DB (Android native)
  └── SQLiteOpenHelper            └── ScoreDBHelper extends SQLiteOpenHelper
       (song.db)                       (score.db: info/player/score/scorelog/scoredatalog)
  ↑ 同一连接管理体系，WAL 模式允许并发读写，busy_timeout 统一处理 ↑
```

### 具体步骤

#### Step 1: 新建 `ScoreDBHelper` (SQLiteOpenHelper)

在 `bms.player.beatoraja.score` 包下新建 `ScoreDBHelper`，统一管理 score.db 的所有表：

```java
class ScoreDBHelper extends SQLiteOpenHelper {
    // 4张表: info, player, score, scorelog, scoredatalog
    // schema version 管理
    // onConfigure: WAL mode
    // onOpen: busy_timeout = 5000
}
```

#### Step 2: 重写 `ScoreDatabaseAccessor`（基于 Android 原生 API）

- 移除 JDBC/QueryRunner 依赖
- 移除 `ReentrantLock`（SQLiteDatabase 自身线程安全 + WAL 模式）
- 改用 `SQLiteDatabase.rawQuery()` 和参数化查询
- `insert()` 改用 `SQLiteDatabase.insertWithOnConflict()`

#### Step 3: 合并 ScoreLog 和 ScoreDataLog

- `ScoreLogDatabaseAccessor` 的逻辑合并到 `ScoreDatabaseAccessor`
- `ScoreDataLogDatabaseAccessor` 的逻辑合并到 `ScoreDatabaseAccessor`
- 两个旧类标记为 `@Deprecated`

#### Step 4: 更新 `PlayDataAccessor`

- 移除 `scorelogdb` 和 `scoredatalogdb` 字段
- 所有操作通过新的统一 `ScoreDatabaseAccessor` 进行

#### Step 5: 数据迁移

- 首次启动时检测旧的 `scorelog.db` 和 `scoredatalog.db` 是否存在
- 如果存在，使用 `ATTACH DATABASE` 或直接读取旧表数据迁移到新 score.db
- 迁移完成后删除旧文件

#### Step 6: 更新 `RivalDataAccessor`

- 更新 rival score 读取逻辑以适配新 API

### 文件变更清单

| 文件 | 操作 |
|------|------|
| `ScoreDatabaseAccessor.java` | 完全重写（Android 原生 API） |
| `ScoreLogDatabaseAccessor.java` | 弃用，逻辑合并到 ScoreDatabaseAccessor |
| `ScoreDataLogDatabaseAccessor.java` | 弃用，逻辑合并到 ScoreDatabaseAccessor |
| `PlayDataAccessor.java` | 更新：移除多 DB 引用 |
| `RivalDataAccessor.java` | 更新：适配新 API |
| `DatabaseUtils.java` | 可选保留（仅 rival 功能可能仍需 JDBC） |
| `SQLiteDatabaseAccessor.java` | 可保留（仅用于表结构定义） |
| 新增 `ScoreDBHelper.java` | SQLiteOpenHelper 实现 |

### 风险与缓解

1. **数据丢失风险**: 迁移脚本需先备份旧文件 → 逐表迁移 → 验证 → 删除旧文件
2. **性能回退**: Android 原生 API 理论上比 JDBC 更轻量，但需 benchmark 验证批量写入性能
3. **兼容性**: 旧版本已部署的用户需无缝迁移
4. **Rival 功能**: 如果 rival 功能依赖读取外部 score.db 文件（不同格式），可能需要保留一个 JDBC 兼容读层

---

## 下一步

1. 确认方案后，按 Step 1-6 逐文件实施
2. 每个 Step 完成后运行现有测试确保无回归
3. 最终在真机上压测 Result 界面的稳定性
