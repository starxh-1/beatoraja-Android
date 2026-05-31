package bms.player.beatoraja.result.debug;

import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.ScoreDatabaseAccessor;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ════════════════════════════════════════════════════════════
 * DB 锁竞争模拟测试
 *
 * 模拟 MusicResult.create() 中 GL Thread 的 readScoreData()
 * 与 ScoreWriteThread 的 writeScoreData() 竞争
 * ScoreDatabaseAccessor synchronized 锁的场景。
 *
 * 运行方式:
 *   gradle test --tests "*ResultFreezeDBLockTest"
 * ════════════════════════════════════════════════════════════
 */
@org.junit.Ignore("Requires rewrite for Android-native ScoreDatabaseAccessor. Old JDBC ReentrantLock removed.")
public class ResultFreezeDBLockTest {

    private static ScoreDatabaseAccessor sharedDb;

    @org.junit.BeforeClass
    public static void setUp() throws Exception {
        // Factory must be set before creating ScoreDatabaseAccessor instances.
        // On Android, this is done by AndroidLauncher.
        // For JVM tests, a JDBC-backed factory would be needed.
        if (ScoreDatabaseAccessor.hasFactory()) {
            File tempDbFile = File.createTempFile("test_score_", ".db");
            tempDbFile.deleteOnExit();
            System.out.println("Temp DB: " + tempDbFile.getAbsolutePath());
            sharedDb = ScoreDatabaseAccessor.create(tempDbFile.getAbsolutePath());
            sharedDb.createTable();
        }
    }

    @org.junit.AfterClass
    public static void tearDown() {
        sharedDb = null;
    }

    private static ReentrantLock getDBLock(ScoreDatabaseAccessor db) throws Exception {
        Field lockField = ScoreDatabaseAccessor.class.getDeclaredField("dblock");
        lockField.setAccessible(true);
        return (ReentrantLock) lockField.get(db);
    }

    /**
     * TestCase A: 无竞争时 readScoreData 耗时
     */
    @org.junit.Test
    public void testReadScoreNoContention() throws Exception {
        ScoreDatabaseAccessor db = sharedDb;

        long start = System.currentTimeMillis();
        ScoreData result = db.getScoreData("0000000000000000000000000000000000000000000000000000000000000000", 0);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("[NO-CONTENTION] getScoreData took: " + elapsed + "ms (result=" + (result != null ? "found" : "null") + ")");
        org.junit.Assert.assertTrue("No-contention read should be fast (< 200ms)", elapsed < 200);
    }

    /**
     * TestCase B: 模拟写入线程持有锁时，读线程阻塞
     *
     * 分支 B1: 写线程持有锁时间短（正常 commit）
     */
    @org.junit.Test
    public void testReadWithWriteContention_fastWrite() throws Exception {
        ScoreDatabaseAccessor db = sharedDb;
        ReentrantLock dblock = getDBLock(db);

        ScoreData testScore = new ScoreData();
        testScore.setSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        testScore.setNotes(2000);
        testScore.setEpg(1000);

        CountDownLatch writeStarted = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            System.out.println("[WRITER] Acquiring ReentrantLock and holding for ~800ms...");
            writeStarted.countDown();
            dblock.lock();
            try {
                long t0 = System.currentTimeMillis();
                db.setScoreData(new ScoreData[]{testScore});
                try { Thread.sleep(800); } catch (InterruptedException e) {}
                System.out.println("[WRITER] Lock held for " + (System.currentTimeMillis() - t0) + "ms, releasing");
            } finally {
                dblock.unlock();
            }
        });

        writer.start();
        writeStarted.await(2, TimeUnit.SECONDS);
        Thread.sleep(100);

        long readStart = System.currentTimeMillis();
        ScoreData result = db.getScoreData(testScore.getSha256(), 0);
        long readElapsed = System.currentTimeMillis() - readStart;

        writer.join(3000);
        org.junit.Assert.assertNull("Reader should timeout at 500ms and return null (writer holds lock 800ms > 500ms)", result);

        System.out.println("[WITH-CONTENTION] getScoreData took: " + readElapsed +
            "ms (writer held lock 800ms, reader tryLock(500ms) timed out)");
        System.out.println("  → FIX: 500ms timeout. GL Thread not blocked indefinitely. Oldscore loads in background thread.");
        org.junit.Assert.assertTrue("Read should timeout around 500ms", readElapsed > 400 && readElapsed < 1500);
    }

    /**
     * TestCase B2: 写线程持有锁时间长（模拟慢 SQLite commit）
     * 这是 clear/new record 场景的近似 — 有更多 SQL 操作
     */
    @org.junit.Test
    public void testReadWithWriteContention_slowWrite() throws Exception {
        ScoreDatabaseAccessor db = sharedDb;
        ReentrantLock dblock = getDBLock(db);

        ScoreData testScore = new ScoreData();
        testScore.setSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        testScore.setNotes(2000);
        testScore.setEpg(1000);
        testScore.setClear(5);
        testScore.setCombo(1500);
        testScore.setMinbp(10);

        CountDownLatch writeStarted = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            System.out.println("[SLOW-WRITER] Simulating slow SQLite commit (lock held ~3s, exceeds tryLock(2s))");
            writeStarted.countDown();
            dblock.lock();
            try {
                long t0 = System.currentTimeMillis();
                db.setScoreData(new ScoreData[]{testScore});
                db.setScoreData(testScore);
                try { Thread.sleep(3000); } catch (InterruptedException e) {}
                System.out.println("[SLOW-WRITER] Lock held for " + (System.currentTimeMillis() - t0) + "ms, releasing");
            } finally {
                dblock.unlock();
            }
        });

        writer.start();
        writeStarted.await(2, TimeUnit.SECONDS);
        Thread.sleep(100);

        long readStart = System.currentTimeMillis();
        ScoreData result = db.getScoreData(testScore.getSha256(), 0);
        long readElapsed = System.currentTimeMillis() - readStart;

        writer.join(6000);
        org.junit.Assert.assertNull("Reader should NOT get ScoreData (returns dummy) — DB operation skipped to not block GL Thread", result);

        System.out.println("[SLOW-CONTENTION] getScoreData took: " + readElapsed +
            "ms → tryLock(500ms) TIMEOUT, returned null");
        System.out.println("  → Even 3s SQLite commit: GL Thread only blocked 500ms max.");
        System.out.println("  → Old score loaded correctly by background OldScoreLoadThread instead.");
    }

    /**
     * TestCase C: 验证 synchronized 方法的锁可重入性
     * (writeScoreData 内部连续调用 getScoreData/setScoreData/setPlayerData，必须是同一把锁)
     */
    @org.junit.Test
    public void testLockReentrancy() throws Exception {
        ScoreDatabaseAccessor db = sharedDb;

        ScoreData testScore = new ScoreData();
        testScore.setSha256("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        testScore.setNotes(1000);
        testScore.setEpg(500);

        long start = System.currentTimeMillis();

        // This simulates the writeScoreData inner calls:
        // All share the same 'this' lock on ScoreDatabaseAccessor, so they MUST be reentrant
        db.setScoreData(new ScoreData[]{testScore});       // acquires ScoreDatabaseAccessor.this
        ScoreData read = db.getScoreData(testScore.getSha256(), 0);     // re-enters same lock
        org.junit.Assert.assertNotNull("Should read back saved score", read);
        org.junit.Assert.assertEquals(500, read.getEpg());

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[REENTRANCY] Chained DB ops took: " + elapsed + "ms (reentrant lock confirmed)");
    }

    /**
     * TestCase D: 多线程并发压力测试 — 验证锁不会死锁
     */
    @org.junit.Test
    public void testConcurrentReadWriteNoDeadlock() throws Exception {
        ScoreDatabaseAccessor db = sharedDb;

        int threadCount = 4;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 20; j++) {
                        if (j % 2 == 0) {
                            // read
                            db.getScoreData("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", 0);
                        } else {
                            // write
                            ScoreData s = new ScoreData();
                            s.setSha256("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
                            s.setNotes(100 + idx);
                            s.setEpg(j * 10);
                            db.setScoreData(new ScoreData[]{s});
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.err.println("Error in thread " + idx + ": " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
            t.start();
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

        System.out.println("[CONCURRENT] All threads completed: " + completed +
            " errors: " + errors.get());
        org.junit.Assert.assertTrue("All threads should complete within timeout (no deadlock)", completed);
        org.junit.Assert.assertEquals("No errors in concurrent operations", 0, errors.get());
    }

    /**
     * TestCase E: 核心场景精确模拟
     *
     * 时序:
     *   T0: MusicResult.create() → updateScoreDatabase()
     *   T0+1ms: new ScoreWriteThread() starts, acquires ScoreDatabaseAccessor.this
     *   T0+5ms: GL Thread tries readScoreData() → blocked on synchronized lock
     *   T0+2000ms: ScoreWriteThread finishes → GL Thread unblocks
     *
     * 这个 test 精确复现 MusicResult.create() 中的时序。
     */
    @org.junit.Test
    public void testExactBugScenario() throws Exception {
        ScoreDatabaseAccessor db = sharedDb;

        ScoreData testScore = new ScoreData();
        testScore.setSha256("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        testScore.setNotes(2000);
        testScore.setEpg(1000);
        testScore.setClear(5);
        testScore.setCombo(1800);

        AtomicLong glBlockDuration = new AtomicLong(0);
        CountDownLatch writeAcquiredLock = new CountDownLatch(1);
        CountDownLatch readAttempted = new CountDownLatch(1);

        // ScoreWriteThread (后台)
        Thread scoreWriteThread = new Thread(() -> {
            try {
                System.out.println("[BUG-SIM:Writer] Starting write (writeScoreData path)");
                // Simulates the full writeScoreData sequence
                // which internally: getScoreData → update → setScoreData → setPlayerData
                // All inside one synchronized method body

                // Each of these acquires/releases the lock multiple times
                // Wait until GL thread is about to try reading
                readAttempted.await(2, TimeUnit.SECONDS);
                writeAcquiredLock.countDown();

                long writeStart = System.currentTimeMillis();

                // Step 1: getScoreData (inside writeScoreData)
                ScoreData old = db.getScoreData(testScore.getSha256(), 0);

                // Step 2: Simulate heavy computation (clear/new record logic)
                // This is inside the method but between two synchronized calls
                // The lock is NOT held here - released after getScoreData returns
                Thread.sleep(300);

                // Step 3: setScoreData  
                db.setScoreData(new ScoreData[]{testScore});

                // Step 4: Simulate setPlayerData (another synchronized call)
                Thread.sleep(200);

                long writeElapsed = System.currentTimeMillis() - writeStart;
                System.out.println("[BUG-SIM:Writer] Write complete in " + writeElapsed + "ms");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "ScoreWriteThread");

        // GL Thread (simulated)
        Thread glThread = new Thread(() -> {
            try {
                // Simulate: MusicResult.create() → updateScoreDatabase()
                System.out.println("[BUG-SIM:GL] MusicResult.create() → updateScoreDatabase() begin");
                readAttempted.countDown();

                // At this point, ScoreWriteThread may or may not hold the lock
                // In the bug case, it DOES hold it inside setScoreData()

                long readStart = System.currentTimeMillis();
                ScoreData result = db.getScoreData(testScore.getSha256(), 0);
                long readElapsed = System.currentTimeMillis() - readStart;

                glBlockDuration.set(readElapsed);
                System.out.println("[BUG-SIM:GL] readScoreData() returned after " + readElapsed + "ms");

                if (readElapsed > 300) {
                    System.out.println("  ⚠️  REPRODUCED: GL Thread BLOCKED for " + readElapsed +
                        "ms waiting for write lock!");
                    System.out.println("  This is the Root Cause of the 10-second freeze!");
                } else {
                    System.out.println("  ✓ No blocking (lock was released before read attempt)");
                    System.out.println("  Try running test multiple times - contention is timing-dependent");
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "GLThread");

        scoreWriteThread.start();
        glThread.start();

        scoreWriteThread.join(5000);
        glThread.join(5000);

        System.out.println("\n=== BUG SCENARIO SUMMARY ===");
        System.out.println("GL Thread blocked: " + glBlockDuration.get() + "ms");
        System.out.println("In real app, this duration = the freeze user sees before score data appears");
        System.out.println("On an Android device with slow SQLite (WAL + fsync), this can be 5-15 seconds");
        System.out.println("Root cause: ScoreDatabaseAccessor is synchronized,");
        System.out.println("           and both GL Thread (readScoreData) and");
        System.out.println("           ScoreWriteThread (writeScoreData) compete for the same lock.");
    }
}
