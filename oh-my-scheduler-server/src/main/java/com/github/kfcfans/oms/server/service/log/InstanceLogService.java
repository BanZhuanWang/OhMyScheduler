package com.github.kfcfans.oms.server.service.log;

import com.github.kfcfans.oms.common.TimeExpressionType;
import com.github.kfcfans.oms.common.model.InstanceLogContent;
import com.github.kfcfans.oms.common.utils.CommonUtils;
import com.github.kfcfans.oms.server.persistence.StringPage;
import com.github.kfcfans.oms.server.persistence.core.model.JobInfoDO;
import com.github.kfcfans.oms.server.persistence.local.LocalInstanceLogDO;
import com.github.kfcfans.oms.server.persistence.local.LocalInstanceLogRepository;
import com.github.kfcfans.oms.server.persistence.mongodb.InstanceLogMetadata;
import com.github.kfcfans.oms.server.service.instance.InstanceManager;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 任务实例运行时日志服务
 *
 * @author tjq
 * @since 2020/4/27
 */
@Slf4j
@Service
public class InstanceLogService {

    // 直接操作 mongoDB 文件系统
    private GridFsTemplate gridFsTemplate;

    // 本地数据库操作bean
    @Resource(name = "localTransactionTemplate")
    private TransactionTemplate localTransactionTemplate;
    @Resource
    private LocalInstanceLogRepository localInstanceLogRepository;

    // 本地维护了在线日志的任务实例ID
    private final Map<Long, Long> instanceId2LastReportTime = Maps.newConcurrentMap();
    private final ExecutorService workerPool;

    // 格式化时间戳
    private static final FastDateFormat dateFormat = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS");
    // 用户路径
    private static final String USER_HOME = System.getProperty("user.home", "oms");
    // 每一个展示的行数
    private static final int MAX_LINE_COUNT = 100;
    // 过期时间
    private static final long EXPIRE_INTERVAL_MS = 60000;

    public InstanceLogService() {
        int coreSize = Runtime.getRuntime().availableProcessors();
        workerPool = new ThreadPoolExecutor(coreSize, coreSize, 1, TimeUnit.MINUTES, Queues.newLinkedBlockingQueue());
    }

    /**
     * 提交日志记录，持久化到本地数据库中
     * @param workerAddress 上报机器地址
     * @param logs 任务实例运行时日志
     */
    public void submitLogs(String workerAddress, List<InstanceLogContent> logs) {

        List<LocalInstanceLogDO> logList = logs.stream().map(x -> {
            instanceId2LastReportTime.put(x.getInstanceId(), System.currentTimeMillis());

            LocalInstanceLogDO y = new LocalInstanceLogDO();
            BeanUtils.copyProperties(x, y);
            y.setWorkerAddress(workerAddress);
            return y;
        }).collect(Collectors.toList());

        try {
            CommonUtils.executeWithRetry0(() -> localInstanceLogRepository.saveAll(logList));
        }catch (Exception e) {
            log.warn("[InstanceLogService] persistent instance logs failed, these logs will be dropped: {}.", logs, e);
        }
    }

    /**
     * 获取任务实例运行日志（默认存在本地数据，需要由生成完成请求的路由与转发）
     * @param instanceId 任务实例ID
     * @param index 页码，从0开始
     * @return 文本字符串
     */
    public StringPage fetchInstanceLog(Long instanceId, long index) {
        try {
            Future<File> fileFuture = prepareLogFile(instanceId);
            File logFile = fileFuture.get(5, TimeUnit.SECONDS);

            // 分页展示数据
            long lines = 0;
            StringBuilder sb = new StringBuilder();
            String lineStr;
            long left = index * MAX_LINE_COUNT;
            long right = left + MAX_LINE_COUNT;
            try (LineNumberReader lr = new LineNumberReader(new FileReader(logFile))) {
                while ((lineStr = lr.readLine()) != null) {

                    // 指定范围内，读出
                    if (lines >= left && lines < right) {
                        sb.append(lineStr).append(System.lineSeparator());
                    }
                    ++lines;
                }
            }catch (Exception e) {
                log.warn("[InstanceLogService] read logFile from disk failed.", e);
                return StringPage.simple("oms-server execution exception, caused by " + ExceptionUtils.getRootCauseMessage(e));
            }

            double totalPage = Math.ceil(1.0 * lines / MAX_LINE_COUNT);
            return new StringPage(index, (long) totalPage, sb.toString());

        }catch (TimeoutException te) {
            return StringPage.simple("log file is being prepared, please try again later.");
        }catch (Exception e) {
            log.warn("[InstanceLogService] fetchInstanceLog failed for instance(instanceId={}).", instanceId, e);
            return StringPage.simple("oms-server execution exception, caused by " + ExceptionUtils.getRootCauseMessage(e));
        }
    }

    /**
     * 下载全部的任务日志文件
     * @param instanceId 任务实例ID
     * @return 日志文件
     * @throws Exception 异常
     */
    public File downloadInstanceLog(long instanceId) throws Exception {
        Future<File> fileFuture = prepareLogFile(instanceId);
        return fileFuture.get(1, TimeUnit.MINUTES);
    }

    /**
     * 异步准备日志文件
     * @param instanceId 任务实例ID
     * @return 异步结果
     */
    private Future<File> prepareLogFile(long instanceId) {
        return workerPool.submit(() -> {
            // 在线日志还在不断更新，需要使用本地数据库中的数据
            if (instanceId2LastReportTime.containsKey(instanceId)) {
                return genTemporaryLogFile(instanceId);
            }
            return genStableLogFile(instanceId);
        });
    }

    /**
     * 将本地的任务实例运行日志同步到 mongoDB 存储，在任务执行结束后异步执行
     * @param instanceId 任务实例ID
     */
    @Async("omsCommonPool")
    public void sync(Long instanceId) {

        // 休眠10秒等待全部数据上报（OmsLogHandler 每隔5秒上报数据）
        try {
            TimeUnit.SECONDS.sleep(10);
        }catch (Exception ignore) {
        }

        Stopwatch sw = Stopwatch.createStarted();
        try {
            // 先持久化到本地文件
            File stableLogFile = genStableLogFile(instanceId);
            // 将文件推送到 MongoDB
            if (gridFsTemplate != null) {
                try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(stableLogFile))) {

                    InstanceLogMetadata metadata = new InstanceLogMetadata();
                    metadata.setInstanceId(instanceId);
                    metadata.setFileSize(stableLogFile.length());
                    metadata.setCreatedTime(System.currentTimeMillis());
                    gridFsTemplate.store(bis, genMongoFileName(instanceId), metadata);
                    log.info("[InstanceLogService] push local instanceLogs(instanceId={}) to mongoDB succeed, using: {}.", instanceId, sw.stop());
                }catch (Exception e) {
                    log.warn("[InstanceLogService] push local instanceLogs(instanceId={}) to mongoDB failed.", instanceId, e);
                }
            }
        }catch (Exception e) {
            log.warn("[InstanceLogService] sync local instanceLogs(instanceId={}) failed.", instanceId, e);
        }
        // 删除本地数据库数据
        try {
            CommonUtils.executeWithRetry0(() -> localInstanceLogRepository.deleteByInstanceId(instanceId));
            instanceId2LastReportTime.remove(instanceId);
        }catch (Exception e) {
            log.warn("[InstanceLogService] delete local instanceLog(instanceId={}) failed.", instanceId, e);
        }
    }

    private File genTemporaryLogFile(long instanceId) {
        String path = genLogFilePath(instanceId, false);
        synchronized (("tpFileLock-" + instanceId).intern()) {

            // Stream 需要在事务的包裹之下使用
            return localTransactionTemplate.execute(status -> {
                File f = new File(path);
                // 如果文件存在且有效，则不再重新构建日志文件（这个判断也需要放在锁内，否则构建到一半的文件会被返回）
                if (f.exists() && (System.currentTimeMillis() - f.lastModified()) < EXPIRE_INTERVAL_MS) {
                    return f;
                }

                // 创建父文件夹（文件在开流时自动会被创建）
                if (!f.getParentFile().exists()) {
                    if (!f.getParentFile().mkdirs()) {
                        throw new RuntimeException("create dir failed");
                    }
                }
                // 重新构建文件
                try (Stream<LocalInstanceLogDO> allLogStream = localInstanceLogRepository.findByInstanceIdOrderByLogTime(instanceId)) {
                    stream2File(allLogStream, f);
                }
                return f;
            });
        }
    }

    private File genStableLogFile(long instanceId) {
        String path = genLogFilePath(instanceId, true);
        synchronized (("stFileLock-" + instanceId).intern()) {
            return localTransactionTemplate.execute(status -> {
                File f = new File(path);
                if (f.exists()) {
                    return f;
                }

                // 创建父文件夹（文件在开流时自动会被创建）
                if (!f.getParentFile().exists()) {
                    if (!f.getParentFile().mkdirs()) {
                        throw new RuntimeException("create dir failed");
                    }
                }

                // 本地存在数据，从本地持久化（对应 SYNC 的情况）
                if (instanceId2LastReportTime.containsKey(instanceId)) {
                    try (Stream<LocalInstanceLogDO> allLogStream = localInstanceLogRepository.findByInstanceIdOrderByLogTime(instanceId)) {
                        stream2File(allLogStream, f);
                    }
                }else {

                    if (gridFsTemplate == null) {
                        string2File("SYSTEM: There is no local log for this task now, you need to use mongoDB to store the past logs.", f);
                        return f;
                    }

                    // 否则从 mongoDB 拉取数据（对应后期查询的情况）
                    GridFsResource gridFsResource = gridFsTemplate.getResource(genMongoFileName(instanceId));

                    if (!gridFsResource.exists()) {
                        string2File("SYSTEM: There is no online log for this job instance.", f);
                        return f;
                    }
                    gridFs2File(gridFsResource, f);
                }
                return f;
            });
        }
    }

    /**
     * 将数据库中存储的日志流转化为磁盘日志文件
     * @param stream 流
     * @param logFile 目标日志文件
     */
    private void stream2File(Stream<LocalInstanceLogDO> stream, File logFile) {
        try (FileWriter fw = new FileWriter(logFile); BufferedWriter bfw = new BufferedWriter(fw)) {
            stream.forEach(instanceLog -> {
                try {
                    bfw.write(convertLog(instanceLog) + System.lineSeparator());
                }catch (Exception ignore) {
                }
            });
        }catch (IOException ie) {
            ExceptionUtils.rethrow(ie);
        }
    }

    /**
     * 将MongoDB中存储的日志持久化为磁盘日志
     * @param gridFsResource mongoDB 文件资源
     * @param logFile 本地文件资源
     */
    private void gridFs2File(GridFsResource gridFsResource, File logFile) {
        byte[] buffer = new byte[1024];
        try (BufferedInputStream gis = new BufferedInputStream(gridFsResource.getInputStream());
             BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(logFile))
        ) {
            while (gis.read(buffer) != -1) {
                bos.write(buffer);
            }
            bos.flush();
        }catch (IOException ie) {
            ExceptionUtils.rethrow(ie);
        }
    }

    private void string2File(String content, File logFile) {
        try(FileWriter fw = new FileWriter(logFile)) {
            fw.write(content);
        }catch (IOException ie) {
            ExceptionUtils.rethrow(ie);
        }
    }


    /**
     * 拼接日志 -> 2020-04-29 22:07:10.059 192.168.1.1:2777 INFO XXX
     * @param instanceLog 日志对象
     * @return 字符串
     */
    private static String convertLog(LocalInstanceLogDO instanceLog) {
        return String.format("%s [%s] -%s", dateFormat.format(instanceLog.getLogTime()), instanceLog.getWorkerAddress(), instanceLog.getLogContent());
    }


    @Async("omsTimingPool")
    @Scheduled(fixedDelay = 60000)
    public void timingCheck() {

        // 1. 定时删除秒级任务的日志
        List<Long> frequentInstanceIds = Lists.newLinkedList();
        instanceId2LastReportTime.keySet().forEach(instanceId -> {
            JobInfoDO jobInfo = InstanceManager.fetchJobInfo(instanceId);
            if (jobInfo == null) {
                return;
            }

            if (TimeExpressionType.frequentTypes.contains(jobInfo.getTimeExpressionType())) {
                frequentInstanceIds.add(instanceId);
            }
        });

        if (!CollectionUtils.isEmpty(frequentInstanceIds)) {
            // 只保留最近10分钟的日志
            long time = System.currentTimeMillis() - 10 * 60 * 1000;
            Lists.partition(frequentInstanceIds, 100).forEach(p -> {
                try {
                    localInstanceLogRepository.deleteByInstanceIdInAndLogTimeLessThan(p, time);
                }catch (Exception e) {
                    log.warn("[InstanceLogService] delete expired logs for instance: {} failed.", p, e);
                }
            });
        }

        // 2. 删除长时间未 REPORT 的日志
    }

    public static String genLogDirPath() {
        return USER_HOME + "/oms-server/online_log/";
    }

    private static String genLogFilePath(long instanceId, boolean stable) {
        if (stable) {
            return genLogDirPath() + String.format("%d-stable.log", instanceId);
        }else {
            return genLogDirPath() + String.format("%d-temporary.log", instanceId);
        }
    }
    private static String genMongoFileName(long instanceId) {
        return String.format("oms-%d.log", instanceId);
    }

    @Autowired(required = false)
    public void setGridFsTemplate(GridFsTemplate gridFsTemplate) {
        this.gridFsTemplate = gridFsTemplate;
    }
}
