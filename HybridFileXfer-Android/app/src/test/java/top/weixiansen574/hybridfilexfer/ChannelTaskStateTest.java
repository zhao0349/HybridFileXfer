package top.weixiansen574.hybridfilexfer;

import org.junit.Test;

import static org.junit.Assert.*;

public class ChannelTaskStateTest {
    @Test public void recordsActualAssignedRangeAndDirection() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        assertTrue(channel.task("synthetic/first.bin", true, 1048576, 524288, 4194304));
        assertEquals(ChannelTaskState.Kind.TASK, channel.kind());
        assertEquals("synthetic/first.bin", channel.task().path);
        assertTrue(channel.task().upload);
        assertEquals(1048577, channel.task().firstByte());
        assertEquals(1572864, channel.task().lastByte());
        assertEquals(4194304, channel.task().fileSize);
        assertTrue(channel.task("synthetic/second.bin", false, 0, 8, 8));
        assertFalse(channel.task().upload);
        assertEquals("synthetic/second.bin", channel.task().path);
    }

    @Test public void idleClearsOnlyTheActiveTask() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        channel.task("synthetic/file.bin", true, 0, 8, 8);
        assertTrue(channel.idle());
        assertEquals(ChannelTaskState.Kind.IDLE, channel.kind());
        assertNull(channel.task());
        assertFalse(channel.idle());
        assertTrue(channel.task("synthetic/next.bin", true, 8, 8, 16));
    }

    @Test public void completedStateSurvivesLateIdleAndTasks() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        channel.task("synthetic/file.bin", true, 0, 8, 8);
        assertTrue(channel.complete("completed"));
        assertNull(channel.task());
        assertFalse(channel.idle());
        assertFalse(channel.task("synthetic/late.bin", true, 0, 8, 8));
        channel.end();
        assertEquals(ChannelTaskState.Kind.COMPLETE, channel.kind());
        assertEquals("completed", channel.message());
        assertFalse(channel.error("late error"));
    }

    @Test public void failedChannelRemainsFailedWhileOtherChannelsContinue() {
        ChannelTaskState failed = new ChannelTaskState();
        ChannelTaskState healthy = new ChannelTaskState();
        failed.begin();
        healthy.begin();
        failed.task("synthetic/file.bin", true, 0, 8, 16);
        healthy.task("synthetic/file.bin", true, 8, 8, 16);
        assertTrue(failed.error("channel disconnected"));
        assertNull(failed.task());
        assertFalse(failed.idle());
        assertFalse(failed.complete("completed"));
        assertFalse(failed.task("synthetic/late.bin", true, 0, 8, 8));
        assertEquals(ChannelTaskState.Kind.ERROR, failed.kind());
        assertEquals("channel disconnected", failed.message());
        assertEquals(ChannelTaskState.Kind.TASK, healthy.kind());
        assertTrue(healthy.task("synthetic/retry.bin", true, 0, 8, 16));
    }

    @Test public void cancelStopAndFinishRejectQueuedActivityUntilNextTransfer() {
        ChannelTaskState channel = new ChannelTaskState();
        assertFalse(channel.task("synthetic/before.bin", true, 0, 8, 8));
        channel.begin();
        channel.task("synthetic/file.bin", true, 0, 8, 8);
        channel.end();
        assertEquals(ChannelTaskState.Kind.IDLE, channel.kind());
        assertNull(channel.task());
        assertFalse(channel.task("synthetic/late.bin", true, 0, 8, 8));
        assertFalse(channel.idle());
        assertFalse(channel.complete("late completion"));
        assertFalse(channel.error("late error"));
        channel.begin();
        assertTrue(channel.task("synthetic/new-transfer.bin", false, 8, 8, 16));
        assertEquals("synthetic/new-transfer.bin", channel.task().path);
    }

    @Test public void nextClientTransferResetsCompletionAndFailure() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        channel.complete("first transfer complete");
        channel.end();
        channel.begin();
        assertEquals(ChannelTaskState.Kind.IDLE, channel.kind());
        assertEquals("", channel.message());
        assertTrue(channel.task("synthetic/second.bin", false, 0, 8, 8));
        channel.error("second transfer failed");
        channel.end();
        channel.begin();
        assertEquals(ChannelTaskState.Kind.IDLE, channel.kind());
        assertEquals("", channel.message());
        assertTrue(channel.task("synthetic/third.bin", true, 0, 8, 8));
    }

    @Test public void resumedLargeFilesKeepLongOffsetsWithoutOverflow() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        long offset = 5L * 1024 * 1024 * 1024 * 1024;
        assertTrue(channel.task("synthetic/large.bin", false, offset, 16, offset + 16));
        assertEquals(offset + 1, channel.task().firstByte());
        assertEquals(offset + 16, channel.task().lastByte());
        assertTrue(channel.task("synthetic/limit.bin", true, Long.MAX_VALUE - 1, 1, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, channel.task().firstByte());
        assertEquals(Long.MAX_VALUE, channel.task().lastByte());
        assertFalse(channel.task("synthetic/overflow.bin", true, Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertEquals("synthetic/limit.bin", channel.task().path);
    }

    @Test public void invalidRangesDoNotReplaceDisplayedTask() {
        ChannelTaskState channel = new ChannelTaskState();
        channel.begin();
        channel.task("synthetic/file.bin", true, 0, 8, 8);
        assertFalse(channel.task(null, true, 0, 8, 8));
        assertFalse(channel.task("synthetic/invalid.bin", true, -1, 8, 8));
        assertFalse(channel.task("synthetic/invalid.bin", true, 0, -1, 8));
        assertFalse(channel.task("synthetic/invalid.bin", true, 0, 8, -1));
        assertFalse(channel.task("synthetic/invalid.bin", true, 9, 0, 8));
        assertFalse(channel.task("synthetic/invalid.bin", true, 4, 8, 8));
        assertEquals("synthetic/file.bin", channel.task().path);
    }
}
