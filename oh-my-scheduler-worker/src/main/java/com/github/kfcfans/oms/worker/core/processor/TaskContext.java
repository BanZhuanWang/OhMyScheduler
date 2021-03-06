package com.github.kfcfans.oms.worker.core.processor;

import com.github.kfcfans.oms.worker.log.OmsLogger;
import lombok.Getter;
import lombok.Setter;

/**
 * 任务上下文
 * 概念统一，所有的worker只处理Task，Job和JobInstance的概念只存在于Server和TaskTracker
 * 单机任务 -> 整个Job变成一个Task
 * 广播任务 -> 整个jOb变成一堆一样的Task
 * MR 任务 -> 被map出来的任务都视为根Task的子Task
 *
 * @author tjq
 * @since 2020/3/18
 */
@Getter
@Setter
public class TaskContext {

    private Long jobId;
    private Long instanceId;
    private Long subInstanceId;
    private String taskId;
    private String taskName;

    /**
     * 通过控制台传递的参数
     */
    private String jobParams;
    /**
     * 通过 OpenAPI 传递的参数
     */
    private String instanceParams;
    /**
     * 最大重试次数
     */
    private int maxRetryTimes;
    /**
     * 当前重试次数
     */
    private int currentRetryTimes;
    /**
     * 子任务对象，通过Map/MapReduce处理器的map方法生成
     */
    private Object subTask;
    /**
     * 在线日志记录
     */
    private OmsLogger omsLogger;

    public String getDescription() {
        return "subInstanceId='" + subInstanceId + '\'' +
                ", instanceId='" + instanceId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", taskName='" + taskName + '\'' +
                ", jobParams='" + jobParams + '\'' +
                ", instanceParams='" + instanceParams;
    }

    @Override
    public String toString() {
        return getDescription();
    }
}
