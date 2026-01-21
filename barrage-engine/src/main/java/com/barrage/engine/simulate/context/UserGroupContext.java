package com.barrage.engine.simulate.context;

import com.barrage.engine.simulate.ExecutionGraph;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 用户组上下文 (User Group Context)。
 * <p>
 * 管理一组虚拟用户的内存资源。本质上是一个大块的堆外内存容器 (Off-heap Memory Container)。
 * 它负责分配和管理 {@code UserSlot}，但不包含具体的 IO 逻辑。
 *
 * @author LettuceLeaves
 * @version 1.0
 * @since 2026/1/6
 */
public class UserGroupContext implements AutoCloseable {

    private final ExecutionGraph graph;
    private final int capacity; // <--- 需要暴露这个字段
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

    /**
     * 初始化下一个用户槽位。
     * <p>
     * 从池中分配一个新的 Slot，并设置其初始 User ID。
     *
     * @return 分配到的 Slot 索引 (0 ~ capacity-1)
     * @throws IllegalStateException 如果组已满
     */
    public int initNextUser() {
        if (activeUserCount >= capacity)
            throw new IllegalStateException("Group Full");

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