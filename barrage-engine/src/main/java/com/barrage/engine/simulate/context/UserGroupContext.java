package com.barrage.engine.simulate.context;

import com.barrage.engine.simulate.ExecutionGraph;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 用户组上下文 (Final)
 * 纯粹的内存容器，不包含 IO 逻辑。
 */
public class UserGroupContext implements AutoCloseable {

    private final ExecutionGraph graph;
    private final int capacity;      // <--- 需要暴露这个字段
    private final long nextUserIdBase;

    // FFM 内存资源
    private final Arena userSlotArena;
    private final MemorySegment userSlots;
    private int activeUserCount = 0;

    public UserGroupContext(ExecutionGraph graph, int capacity, long startId) {
        this.graph = graph;
        this.capacity = capacity;
        this.nextUserIdBase = startId;

        // 分配堆外内存
        this.userSlotArena = Arena.ofShared();
        this.userSlots = userSlotArena.allocate((long) capacity * UserSlotLayout.SLOT_SIZE, 64);
    }

    public int initNextUser() {
        if (activeUserCount >= capacity) throw new IllegalStateException("Group Full");

        int index = activeUserCount;
        long userId = nextUserIdBase + index;

        // 初始化 ID
        long offset = (long) index * UserSlotLayout.SLOT_SIZE;
        userSlots.set(ValueLayout.JAVA_LONG, offset + UserSlotLayout.OFFSET_USER_ID, userId);

        activeUserCount++;
        return index;
    }

    // --- 缺失的 Getter ---
    public int getCapacity() {
        return capacity;
    }

    public MemorySegment getUserSlotsBlock() {
        return userSlots;
    }

    public ExecutionGraph getGraph() {
        return graph;
    }

    @Override
    public void close() {
        if (userSlotArena.scope().isAlive()) {
            userSlotArena.close();
        }
    }
}