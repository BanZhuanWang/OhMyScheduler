package com.github.kfcfans.oms.server.akka.actors;

import akka.actor.AbstractActor;
import com.github.kfcfans.oms.common.InstanceStatus;
import com.github.kfcfans.oms.common.request.TaskTrackerReportInstanceStatusReq;
import com.github.kfcfans.oms.common.request.WorkerHeartbeat;
import com.github.kfcfans.oms.common.request.WorkerLogReportReq;
import com.github.kfcfans.oms.common.response.AskResponse;
import com.github.kfcfans.oms.server.common.utils.SpringUtils;
import com.github.kfcfans.oms.server.service.log.InstanceLogService;
import com.github.kfcfans.oms.server.service.instance.InstanceManager;
import com.github.kfcfans.oms.server.service.ha.WorkerManagerService;
import lombok.extern.slf4j.Slf4j;

/**
 * 处理 Worker 请求
 *
 * @author tjq
 * @since 2020/3/30
 */
@Slf4j
public class ServerActor extends AbstractActor {

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(WorkerHeartbeat.class, this::onReceiveWorkerHeartbeat)
                .match(TaskTrackerReportInstanceStatusReq.class, this::onReceiveTaskTrackerReportInstanceStatusReq)
                .match(WorkerLogReportReq.class, this::onReceiveWorkerLogReportReq)
                .matchAny(obj -> log.warn("[ServerActor] receive unknown request: {}.", obj))
                .build();
    }


    /**
     * 处理 Worker 的心跳请求
     * @param heartbeat 心跳包
     */
    private void onReceiveWorkerHeartbeat(WorkerHeartbeat heartbeat) {
        WorkerManagerService.updateStatus(heartbeat);
    }

    /**
     * 处理 instance 状态
     * @param req 任务实例的状态上报请求
     */
    private void onReceiveTaskTrackerReportInstanceStatusReq(TaskTrackerReportInstanceStatusReq req) {
        try {
            InstanceManager.updateStatus(req);

            // 结束状态（成功/失败）需要回复消息
            if (!InstanceStatus.generalizedRunningStatus.contains(req.getInstanceStatus())) {
                getSender().tell(AskResponse.succeed(null), getSelf());
            }
        }catch (Exception e) {
            log.error("[ServerActor] update instance status failed for request: {}.", req, e);
        }
    }

    private void onReceiveWorkerLogReportReq(WorkerLogReportReq req) {
        // 这个效率应该不会拉垮吧...也就是一些判断 + Map#get 吧...
        SpringUtils.getBean(InstanceLogService.class).submitLogs(req.getWorkerAddress(), req.getInstanceLogContents());
    }
}
