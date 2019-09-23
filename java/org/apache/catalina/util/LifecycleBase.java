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
package org.apache.catalina.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;

/**
 * Base implementation of the {@link Lifecycle} interface that implements the
 * state transition rules for {@link Lifecycle#start()} and
 * {@link Lifecycle#stop()}
 */
public abstract class LifecycleBase implements Lifecycle {

    private static final Log log = LogFactory.getLog(LifecycleBase.class);

    // 日志国际化输出使用
    private static final StringManager sm = StringManager.getManager(LifecycleBase.class);


    /**
     * 生命周期观察者数组
     * The list of registered LifecycleListeners for event notifications.
     */
    private final List<LifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();


    /**
     * 当前对象的生命周期状态
     * The current state of the source component.
     */
    private volatile LifecycleState state = LifecycleState.NEW;


    /**
     *  失败的时候是否抛出异常
     */
    private boolean throwOnFailure = true;


    /**
     * Will a {@link LifecycleException} thrown by a sub-class during
     * {@link #initInternal()}, {@link #startInternal()},
     * {@link #stopInternal()} or {@link #destroyInternal()} be re-thrown for
     * the caller to handle or will it be logged instead?
     *
     * @return {@code true} if the exception will be re-thrown, otherwise
     *         {@code false}
     */
    public boolean getThrowOnFailure() {
        return throwOnFailure;
    }


    /**
     * Configure if a {@link LifecycleException} thrown by a sub-class during
     * {@link #initInternal()}, {@link #startInternal()},
     * {@link #stopInternal()} or {@link #destroyInternal()} will be re-thrown
     * for the caller to handle or if it will be logged instead.
     *
     * @param throwOnFailure {@code true} if the exception should be re-thrown,
     *                       otherwise {@code false}
     */
    public void setThrowOnFailure(boolean throwOnFailure) {
        this.throwOnFailure = throwOnFailure;
    }


    /**
     * {@inheritDoc}
     * 注册生命周期观察者
     */
    @Override
    public void addLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.add(listener);
    }


    /**
     * {@inheritDoc}
     *
     * 返回一个复制的生命周期观察者数组
     */
    @Override
    public LifecycleListener[] findLifecycleListeners() {
        return lifecycleListeners.toArray(new LifecycleListener[0]);
    }


    /**
     * {@inheritDoc}
     *
     * 移除指定的生命周期观察者
     */
    @Override
    public void removeLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.remove(listener);
    }


    /**
     * Allow sub classes to fire {@link Lifecycle} events.
     * 触发生命周期事件
     * @param type  Event type
     * @param data  Data associated with event.
     */
    protected void fireLifecycleEvent(String type, Object data) {
        LifecycleEvent event = new LifecycleEvent(this, type, data);
        for (LifecycleListener listener : lifecycleListeners) {
            listener.lifecycleEvent(event);
        }
    }


    @Override
    public final synchronized void init() throws LifecycleException {
        // 如果生命周期状态不是  NEW  就打印日志抛异常退出
        if (!state.equals(LifecycleState.NEW)) {
            invalidTransition(Lifecycle.BEFORE_INIT_EVENT);
        }

        // 此时生命周期状态为NEW
        try {
            // 设置生命周期状态为 INITIALIZING 并触发 INITIALIZING 事件
            setStateInternal(LifecycleState.INITIALIZING, null, false);

            // 交给子类实现具体的初始化逻辑
            initInternal();

            // 设置生命周期状态为 INITIALIZED 并触发 INITIALIZED 事件
            setStateInternal(LifecycleState.INITIALIZED, null, false);
        } catch (Throwable t) {
            handleSubClassException(t, "lifecycleBase.initFail", toString());
        }
    }


    /**
     * Sub-classes implement this method to perform any instance initialisation
     * required.
     *
     * @throws LifecycleException If the initialisation fails
     */
    protected abstract void initInternal() throws LifecycleException;


    /**
     * {@inheritDoc}
     */
    @Override
    public final synchronized void start() throws LifecycleException {

        // 如果当前生命周期状态是 STARTING_PREP STARTING STARTED  就打印日志返回
        if (LifecycleState.STARTING_PREP.equals(state) || LifecycleState.STARTING.equals(state) ||
                LifecycleState.STARTED.equals(state)) {

            if (log.isDebugEnabled()) {
                Exception e = new LifecycleException();
                log.debug(sm.getString("lifecycleBase.alreadyStarted", toString()), e);
            } else if (log.isInfoEnabled()) {
                log.info(sm.getString("lifecycleBase.alreadyStarted", toString()));
            }

            return;
        }

        if (state.equals(LifecycleState.NEW)) {
            // 如果当前生命周期状态是 NEW  则进行初始化操作
            init();
        } else if (state.equals(LifecycleState.FAILED)) {
            // 如果当前生命周期状态是 FAILED  则进行停止操作
            stop();
        } else if (!state.equals(LifecycleState.INITIALIZED) &&
                !state.equals(LifecycleState.STOPPED)) {
            // 如果当前生命周期状态 既不是 INITIALIZED 也不是 STOPPED 状态就是报错
            invalidTransition(Lifecycle.BEFORE_START_EVENT);
        }

        try {
            // 设置生命周期状态为 STARTING_PREP 并触发 STARTING_PREP 事件
            setStateInternal(LifecycleState.STARTING_PREP, null, false);

            // 交给子类实现具体的 start 操作
            startInternal();

            // 子类完成start操作后  再检查下是否成功 start


            if (state.equals(LifecycleState.FAILED)) {
                // This is a 'controlled' failure. The component put itself into the
                // FAILED state so call stop() to complete the clean-up.
                // 如果当前生命周期状态是 FAILED 则执行停止操作
                stop();
            } else if (!state.equals(LifecycleState.STARTING)) {
                // Shouldn't be necessary but acts as a check that sub-classes are
                // doing what they are supposed to.
                // 如果当前生命周期状态 不是 STARTING 就抛异常
                invalidTransition(Lifecycle.AFTER_START_EVENT);
            } else {
                // 如果当前生命周期状态是STARTING 就设置状态是 STARTED 并触发 STARTED 事件
                setStateInternal(LifecycleState.STARTED, null, false);
            }
        } catch (Throwable t) {
            // This is an 'uncontrolled' failure so put the component into the
            // FAILED state and throw an exception.
            handleSubClassException(t, "lifecycleBase.startFail", toString());
        }
    }


    /**
     * Sub-classes must ensure that the state is changed to
     * {@link LifecycleState#STARTING} during the execution of this method.
     * Changing state will trigger the {@link Lifecycle#START_EVENT} event.
     *
     * If a component fails to start it may either throw a
     * {@link LifecycleException} which will cause it's parent to fail to start
     * or it can place itself in the error state in which case {@link #stop()}
     * will be called on the failed component but the parent component will
     * continue to start normally.
     *
     * @throws LifecycleException Start error occurred
     */
    protected abstract void startInternal() throws LifecycleException;


    /**
     * {@inheritDoc}
     */
    @Override
    public final synchronized void stop() throws LifecycleException {

        // 如果当前生命周期状态是 STOPPING_PREP STOPPING STOPPED  直接返回
        if (LifecycleState.STOPPING_PREP.equals(state) || LifecycleState.STOPPING.equals(state) ||
                LifecycleState.STOPPED.equals(state)) {

            if (log.isDebugEnabled()) {
                Exception e = new LifecycleException();
                log.debug(sm.getString("lifecycleBase.alreadyStopped", toString()), e);
            } else if (log.isInfoEnabled()) {
                log.info(sm.getString("lifecycleBase.alreadyStopped", toString()));
            }

            return;
        }

        // 如果当前生命周期状态是 NEW  直接把状态改为STOPPED即可
        if (state.equals(LifecycleState.NEW)) {
            state = LifecycleState.STOPPED;
            return;
        }

        // 如果当前生命周期状态 既不是 STARTED 也不是 FAILED 直接抛异常
        if (!state.equals(LifecycleState.STARTED) && !state.equals(LifecycleState.FAILED)) {
            invalidTransition(Lifecycle.BEFORE_STOP_EVENT);
        }

        // 代码走到这来   当前生命周期状态 只能是 STARTED FAILED 两种

        try {
            if (state.equals(LifecycleState.FAILED)) {
                // Don't transition to STOPPING_PREP as that would briefly mark the
                // component as available but do ensure the BEFORE_STOP_EVENT is
                // fired
                // 如果当前生命周期状态是 FAILED 则触发 BEFORE_STOP_EVENT 事件
                fireLifecycleEvent(BEFORE_STOP_EVENT, null);
            } else {
                // 如果当前生命周期状态是 STARTED 则 更新状态为 STOPPING_PREP 并触发 STOPPING_PREP 事件
                setStateInternal(LifecycleState.STOPPING_PREP, null, false);
            }

            // 交给子类处理 执行 stop 操作
            stopInternal();

            // Shouldn't be necessary but acts as a check that sub-classes are
            // doing what they are supposed to.
            // 如果当前状态 既不是 STOPPING 也不是 FAILED 就抛异常
            if (!state.equals(LifecycleState.STOPPING) && !state.equals(LifecycleState.FAILED)) {
                invalidTransition(Lifecycle.AFTER_STOP_EVENT);
            }

            // 更新当前生命周期状态为 STOPPED 并触发 STOPPED
            setStateInternal(LifecycleState.STOPPED, null, false);
        } catch (Throwable t) {
            handleSubClassException(t, "lifecycleBase.stopFail", toString());
        } finally {

            // 如果当前类实例是 是 Lifecycle.SingleUse
            // 则 更新当前生命周期状态为 STOPPED 并触发 STOPPED事件
            // 接着执行析构操作  destroy
            if (this instanceof Lifecycle.SingleUse) {
                // Complete stop process first
                setStateInternal(LifecycleState.STOPPED, null, false);
                destroy();
            }
        }
    }


    /**
     * Sub-classes must ensure that the state is changed to
     * {@link LifecycleState#STOPPING} during the execution of this method.
     * Changing state will trigger the {@link Lifecycle#STOP_EVENT} event.
     *
     * @throws LifecycleException Stop error occurred
     */
    protected abstract void stopInternal() throws LifecycleException;


    @Override
    public final synchronized void destroy() throws LifecycleException {

        // 如果当前生命周期状态为 FAILED  则表明还没有进行 clean-up 操作 所以要先进行 stop操作
        if (LifecycleState.FAILED.equals(state)) {
            try {
                // Triggers clean-up
                stop();
            } catch (LifecycleException e) {
                // Just log. Still want to destroy.
                log.error(sm.getString("lifecycleBase.destroyStopFail", toString()), e);
            }
        }

        // 如果当前生命周期状态为 DESTROYING DESTROYED  则打印日志并返回
        if (LifecycleState.DESTROYING.equals(state) || LifecycleState.DESTROYED.equals(state)) {
            if (log.isDebugEnabled()) {
                Exception e = new LifecycleException();
                log.debug(sm.getString("lifecycleBase.alreadyDestroyed", toString()), e);
            } else if (log.isInfoEnabled() && !(this instanceof Lifecycle.SingleUse)) {
                // Rather than have every component that might need to call
                // destroy() check for SingleUse, don't log an info message if
                // multiple calls are made to destroy()
                log.info(sm.getString("lifecycleBase.alreadyDestroyed", toString()));
            }

            return;
        }

        // 当前生命周期状态必须是 STOPPED FAILED NEW INITIALIZED  否则就抛异常

        if (!state.equals(LifecycleState.STOPPED) && !state.equals(LifecycleState.FAILED) &&
                !state.equals(LifecycleState.NEW) && !state.equals(LifecycleState.INITIALIZED)) {
            invalidTransition(Lifecycle.BEFORE_DESTROY_EVENT);
        }

        try {
            // 更新当前生命周期状态为 DESTROYING 并触发 DESTROYING 事件
            setStateInternal(LifecycleState.DESTROYING, null, false);

            // 交给子类执行 destroy 操作
            destroyInternal();

            // 更新当前生命周期状态为 DESTROYED 并触发 DESTROYED 事件
            setStateInternal(LifecycleState.DESTROYED, null, false);
        } catch (Throwable t) {
            handleSubClassException(t, "lifecycleBase.destroyFail", toString());
        }
    }


    /**
     * Sub-classes implement this method to perform any instance destruction
     * required.
     *
     * @throws LifecycleException If the destruction fails
     */
    protected abstract void destroyInternal() throws LifecycleException;


    /**
     * {@inheritDoc}
     */
    @Override
    public LifecycleState getState() {
        return state;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String getStateName() {
        return getState().toString();
    }


    /**
     * Provides a mechanism for sub-classes to update the component state.
     * Calling this method will automatically fire any associated
     * {@link Lifecycle} event. It will also check that any attempted state
     * transition is valid for a sub-class.
     *
     * @param state The new state for this component
     * @throws LifecycleException when attempting to set an invalid state
     */
    protected synchronized void setState(LifecycleState state) throws LifecycleException {
        setStateInternal(state, null, true);
    }


    /**
     * Provides a mechanism for sub-classes to update the component state.
     * Calling this method will automatically fire any associated
     * {@link Lifecycle} event. It will also check that any attempted state
     * transition is valid for a sub-class.
     *
     * @param state The new state for this component
     * @param data  The data to pass to the associated {@link Lifecycle} event
     * @throws LifecycleException when attempting to set an invalid state
     */
    protected synchronized void setState(LifecycleState state, Object data)
            throws LifecycleException {
        setStateInternal(state, data, true);
    }


    private synchronized void setStateInternal(LifecycleState state, Object data, boolean check)
            throws LifecycleException {

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("lifecycleBase.setState", this, state));
        }

        if (check) {
            // Must have been triggered by one of the abstract methods (assume
            // code in this class is correct)
            // null is never a valid state
            if (state == null) {
                invalidTransition("null");
                // Unreachable code - here to stop eclipse complaining about
                // a possible NPE further down the method
                return;
            }

            // Any method can transition to failed
            // startInternal() permits STARTING_PREP to STARTING
            // stopInternal() permits STOPPING_PREP to STOPPING and FAILED to
            // STOPPING
            if (!(state == LifecycleState.FAILED ||
                    (this.state == LifecycleState.STARTING_PREP &&
                            state == LifecycleState.STARTING) ||
                    (this.state == LifecycleState.STOPPING_PREP &&
                            state == LifecycleState.STOPPING) ||
                    (this.state == LifecycleState.FAILED &&
                            state == LifecycleState.STOPPING))) {
                // No other transition permitted
                invalidTransition(state.name());
            }
        }

        this.state = state;
        String lifecycleEvent = state.getLifecycleEvent();
        if (lifecycleEvent != null) {
            fireLifecycleEvent(lifecycleEvent, data);
        }
    }


    private void invalidTransition(String type) throws LifecycleException {
        String msg = sm.getString("lifecycleBase.invalidTransition", type, toString(), state);
        throw new LifecycleException(msg);
    }


    private void handleSubClassException(Throwable t, String key, Object... args) throws LifecycleException {
        ExceptionUtils.handleThrowable(t);
        setStateInternal(LifecycleState.FAILED, null, false);
        String msg = sm.getString(key, args);
        if (getThrowOnFailure()) {
            if (!(t instanceof LifecycleException)) {
                t = new LifecycleException(msg, t);
            }
            throw (LifecycleException) t;
        } else {
            log.error(msg, t);
        }
    }
}
