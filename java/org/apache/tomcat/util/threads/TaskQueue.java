/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.threads;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.tomcat.util.res.StringManager;

/**
 * As task queue specifically designed to run with a thread pool executor. The
 * task queue is optimised to properly utilize threads within a thread pool
 * executor. If you use a normal queue, the executor will spawn threads when
 * there are idle threads and you wont be able to force items onto the queue
 * itself.
 */
public class TaskQueue extends LinkedBlockingQueue<Runnable> {

    private static final long serialVersionUID = 1L;
    protected static final StringManager sm = StringManager
            .getManager("org.apache.tomcat.util.threads.res");

    private transient volatile ThreadPoolExecutor parent = null;

    // No need to be volatile. This is written and read in a single thread
    // (when stopping a context and firing the  listeners)
    private Integer forcedRemainingCapacity = null;

    public TaskQueue() {
        super();
    }

    public TaskQueue(int capacity) {
        super(capacity);
    }

    public TaskQueue(Collection<? extends Runnable> c) {
        super(c);
    }

    public void setParent(ThreadPoolExecutor tp) {
        parent = tp;
    }

    public boolean force(Runnable o) {
        if ( parent==null || parent.isShutdown() ) throw new RejectedExecutionException(sm.getString("taskQueue.notRunning"));
        return super.offer(o); //forces the item onto the queue, to be used if the task is rejected
    }

    public boolean force(Runnable o, long timeout, TimeUnit unit) throws InterruptedException {
        if ( parent==null || parent.isShutdown() ) throw new RejectedExecutionException(sm.getString("taskQueue.notRunning"));
        return super.offer(o,timeout,unit); //forces the item onto the queue, to be used if the task is rejected
    }

    @Override
    public boolean offer(Runnable o) {
      //we can't do any checks

        // parent 一般不会为null
        if (parent==null) return super.offer(o);

        // 如果线程数已经到了最大值，不能创建新线程了，只能把任务添加到任务队列。
        //we are maxed out on threads, simply queue the object
        if (parent.getPoolSize() == parent.getMaximumPoolSize()) return super.offer(o);


        // 执行到这里，表明当前线程数大于核心线程数，并且小于最大线程数  （把任务提交到队列本身就表示 已经超了和弦线程数）
        // 表明是可以创建新线程的，那到底要不要创建呢？分两种情况：

        //  1. 如果已提交的任务数小于当前线程数，表示还有空闲线程，无需创建新线程
        //we have idle threads, just add it to the queue
        if (parent.getSubmittedCount()<=(parent.getPoolSize())) return super.offer(o);


        // 2. 如果已提交的任务数大于当前线程数，线程不够用了，返回false去创建新线程
        //if we have less threads than maximum force creation of a new thread
        if (parent.getPoolSize()<parent.getMaximumPoolSize()) return false;



        //if we reached here, we need to add it to the queue
        return super.offer(o);
    }


    @Override
    public Runnable poll(long timeout, TimeUnit unit)
            throws InterruptedException {
        Runnable runnable = super.poll(timeout, unit);
        if (runnable == null && parent != null) {
            // the poll timed out, it gives an opportunity to stop the current
            // thread if needed to avoid memory leaks.
            parent.stopCurrentThreadIfNeeded();
        }
        return runnable;
    }

    @Override
    public Runnable take() throws InterruptedException {
        if (parent != null && parent.currentThreadShouldBeStopped()) {
            return poll(parent.getKeepAliveTime(TimeUnit.MILLISECONDS),
                    TimeUnit.MILLISECONDS);
            // yes, this may return null (in case of timeout) which normally
            // does not occur with take()
            // but the ThreadPoolExecutor implementation allows this
        }
        return super.take();
    }

    @Override
    public int remainingCapacity() {
        if (forcedRemainingCapacity != null) {
            // ThreadPoolExecutor.setCorePoolSize checks that
            // remainingCapacity==0 to allow to interrupt idle threads
            // I don't see why, but this hack allows to conform to this
            // "requirement"
            return forcedRemainingCapacity.intValue();
        }
        return super.remainingCapacity();
    }

    public void setForcedRemainingCapacity(Integer forcedRemainingCapacity) {
        this.forcedRemainingCapacity = forcedRemainingCapacity;
    }

}
