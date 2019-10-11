package com.newegg.ec.redis.controller;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import com.newegg.ec.redis.client.RedisClient;
import com.newegg.ec.redis.client.RedisClientFactory;
import com.newegg.ec.redis.entity.Cluster;
import com.newegg.ec.redis.entity.RedisNode;
import com.newegg.ec.redis.entity.Result;
import com.newegg.ec.redis.plugin.install.service.AbstractNodeOperation;
import com.newegg.ec.redis.service.IClusterService;
import com.newegg.ec.redis.service.IMachineService;
import com.newegg.ec.redis.service.IRedisNodeService;
import com.newegg.ec.redis.service.IRedisService;
import com.newegg.ec.redis.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import redis.clients.jedis.HostAndPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @author Jay.H.Zou
 * @date 9/25/2019
 */
@RequestMapping("/nodeManage/*")
@Controller
public class NodeManageController {

    private static final Logger logger = LoggerFactory.getLogger(NodeManageController.class);

    @Autowired
    private IClusterService clusterService;

    @Autowired
    private IRedisService redisService;

    @Autowired
    private IRedisNodeService redisNodeService;

    @Autowired
    private IMachineService machineService;

    /**
     * 在此处理 node 之间的关系
     * 设置 inCluster, runStatus
     *
     * @param clusterId
     * @return
     */
    @RequestMapping(value = "/getAllNodeList/{clusterId}", method = RequestMethod.GET)
    @ResponseBody
    public Result getAllNodeList(@PathVariable("clusterId") Integer clusterId) {
        Cluster cluster = clusterService.getClusterById(clusterId);
        if (cluster == null) {
            return Result.failResult().setMessage("Get cluster failed.");
        }
        List<RedisNode> redisNodeList = redisService.getRedisNodeList(cluster);
        List<RedisNode> redisNodeSorted = redisNodeService.sortRedisNodeList(redisNodeList);
        return Result.successResult(redisNodeSorted);
    }

    @RequestMapping(value = "/getAllNodeListWithStatus/{clusterId}", method = RequestMethod.GET)
    @ResponseBody
    public Result getAllNodeListWithStatus(@PathVariable("clusterId") Integer clusterId) {
        long start = System.currentTimeMillis();
        List<RedisNode> redisNodeSorted = redisNodeService.getRedisNodeListByClusterId(clusterId);
        System.err.println("all: " + (System.currentTimeMillis() - start));
        return Result.successResult(redisNodeSorted);
    }

    /**
     * TODO: jedis 暂无该 API，需自己完成
     *
     * @param redisNodeList
     * @return
     */
    @RequestMapping(value = "/purgeMemory", method = RequestMethod.POST)
    @ResponseBody
    public Result purgeMemory(@RequestBody List<RedisNode> redisNodeList) {
        if (!verifyRedisNodeList(redisNodeList)) {
            return Result.failResult();
        }
        return Result.successResult();
    }

    @RequestMapping(value = "/forget", method = RequestMethod.POST)
    @ResponseBody
    public Result forget(@RequestBody List<RedisNode> redisNodeList) {
        Result result = clusterOperate(redisNodeList, (cluster, redisNode) -> {
            String nodes = cluster.getNodes();
            String node = redisNode.getHost() + SignUtil.COLON + redisNode.getPort();
            if (nodes.contains(node)) {
                logger.warn("I can't forget " + node + ", because it in the database");
                return true;
            }
            return redisService.clusterForget(cluster, redisNode);
        });
        return result;
    }

    @RequestMapping(value = "/moveSlot", method = RequestMethod.POST)
    @ResponseBody
    public Result moveSlot(@RequestBody JSONObject jsonObject) {
        RedisNode redisNode = jsonObject.getObject("redisNode", RedisNode.class);
        SlotBalanceUtil.Shade slot = jsonObject.getObject("slotRange", SlotBalanceUtil.Shade.class);
        Cluster cluster = getCluster(redisNode.getClusterId());
        boolean result = redisService.clusterMoveSlots(cluster, redisNode, slot);
        return result ? Result.successResult() : Result.failResult();
    }

    @RequestMapping(value = "/replicateOf", method = RequestMethod.POST)
    @ResponseBody
    public Result replicateOf(@RequestBody List<RedisNode> redisNodeList) {
        Result result = clusterOperate(redisNodeList, (cluster, redisNode) -> redisService.clusterReplicate(cluster, redisNode.getMasterId(), redisNode));
        return result;
    }

    @RequestMapping(value = "/failOver", method = RequestMethod.POST)
    @ResponseBody
    public Result failOver(@RequestBody List<RedisNode> redisNodeList) {
        Result result = clusterOperate(redisNodeList, (cluster, redisNode) -> redisService.clusterFailOver(cluster, redisNode));
        return result;
    }

    @RequestMapping(value = "/start", method = RequestMethod.POST)
    @ResponseBody
    public Result start(@RequestBody List<RedisNode> redisNodeList) {
        Result result = nodeOperate(redisNodeList, (cluster, redisNode, abstractNodeOperation) -> {
            if (NetworkUtil.telnet(redisNode.getHost(), redisNode.getPort())) {
                return true;
            } else {
                return abstractNodeOperation.start(cluster, redisNode);
            }
        });
        return result;
    }

    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    @ResponseBody
    public Result stop(@RequestBody List<RedisNode> redisNodeList) {
        Result result = nodeOperate(redisNodeList, (cluster, redisNode, abstractNodeOperation) -> {
            if (NetworkUtil.telnet(redisNode.getHost(), redisNode.getPort())) {
                return abstractNodeOperation.stop(cluster, redisNode);
            } else {
                return false;
            }
        });
        return result;
    }

    @RequestMapping(value = "/restart", method = RequestMethod.POST)
    @ResponseBody
    public Result restart(@RequestBody List<RedisNode> redisNodeList) {
        Result result = nodeOperate(redisNodeList, (cluster, redisNode, abstractNodeOperation) -> {
            if (NetworkUtil.telnet(redisNode.getHost(), redisNode.getPort())) {
                return abstractNodeOperation.restart(cluster, redisNode);
            } else {
                return abstractNodeOperation.start(cluster, redisNode);
            }
        });
        return result;
    }

    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    @ResponseBody
    public Result delete(@RequestBody List<RedisNode> redisNodeList) {
        Result result = nodeOperate(redisNodeList, (cluster, redisNode, abstractNodeOperation) -> {
            if (NetworkUtil.telnet(redisNode.getHost(), redisNode.getPort())) {
                return false;
            } else {
                abstractNodeOperation.remove(cluster, redisNode);
                redisNodeService.deleteRedisNodeById(redisNode.getRedisNodeId());
                return true;
            }
        });
        return result;
    }

    @RequestMapping(value = "/editConfig", method = RequestMethod.POST)
    @ResponseBody
    public Result editConfig(@RequestBody List<RedisNode> redisNodeList, RedisConfigUtil.RedisConfig redisConfig) {

        return Result.successResult();
    }

    @RequestMapping(value = "/importNode", method = RequestMethod.POST)
    @ResponseBody
    public Result importNode(@RequestBody List<RedisNode> redisNodeList) {
        try {
            RedisNode firstRedisNode = redisNodeList.get(0);
            Cluster cluster = getCluster(firstRedisNode.getClusterId());
            Set<HostAndPort> hostAndPorts = RedisUtil.nodesToHostAndPortSet(cluster.getNodes());
            HostAndPort hostAndPort = hostAndPorts.iterator().next();
            RedisNode seed = new RedisNode();
            seed.setHost(hostAndPort.getHost());
            seed.setPort(hostAndPort.getPort());
            StringBuilder message = new StringBuilder();
            redisNodeList.forEach(redisNode -> {
                String oneResult = redisService.clusterMeet(cluster, seed, redisNodeList);
                if (Strings.isNullOrEmpty(oneResult)) {
                    message.append(oneResult).append("\n");
                    // TODO: save to db
                    Integer redisNodeId = redisNode.getRedisNodeId();
                    RedisNode exist = redisNodeService.getRedisNodeById(redisNodeId);
                    if (exist == null) {
                        redisNodeService.addRedisNode(redisNode);
                    }
                }
            });
            return Strings.isNullOrEmpty(message.toString())? Result.successResult() : Result.failResult().setMessage(message.toString());
        } catch (Exception e) {
            e.printStackTrace();
            return Result.failResult().setMessage(e.getMessage());
        }
    }

    private Cluster getCluster(Integer clusterId) {
        return clusterService.getClusterById(clusterId);
    }

    private boolean verifyRedisNodeList(List<RedisNode> redisNodeList) {
        return redisNodeList != null && !redisNodeList.isEmpty();
    }

    /**
     * cluster operation
     *
     * @param redisNodeList
     * @param clusterHandler
     * @return
     */
    private Result clusterOperate(List<RedisNode> redisNodeList, ClusterHandler clusterHandler) {
        if (!verifyRedisNodeList(redisNodeList)) {
            return Result.failResult();
        }
        Integer clusterId = redisNodeList.get(0).getClusterId();
        Cluster cluster = getCluster(clusterId);
        StringBuffer messageBuffer = new StringBuffer();
        redisNodeList.forEach(redisNode -> {
            try {
                boolean handleResult = clusterHandler.handle(cluster, redisNode);
                if (!handleResult) {
                    messageBuffer.append(redisNode.getHost() + ":" + redisNode.getPort() + " operation failed.\n");
                }
            } catch (Exception e) {
                logger.error("Operation failed.", e);
            }
        });
        String message = messageBuffer.toString();
        return Strings.isNullOrEmpty(message) ? Result.successResult() : Result.failResult().setMessage(message);
    }

    /**
     * node operation
     *
     * @param redisNodeList
     * @param nodeHandler
     * @return
     */
    private Result nodeOperate(List<RedisNode> redisNodeList, NodeHandler nodeHandler) {
        if (!verifyRedisNodeList(redisNodeList)) {
            return Result.failResult();
        }
        Integer clusterId = redisNodeList.get(0).getClusterId();
        Cluster cluster = getCluster(clusterId);
        AbstractNodeOperation nodeOperation = clusterService.getNodeOperation(cluster.getInstallationEnvironment());
        StringBuffer messageBuffer = new StringBuffer();
        redisNodeList.forEach(redisNode -> {
            try {
                boolean handleResult = nodeHandler.handle(cluster, redisNode, nodeOperation);
                if (!handleResult) {
                    messageBuffer.append(redisNode.getHost() + ":" + redisNode.getPort() + " operation failed.\n");
                }
            } catch (Exception e) {
                logger.error("Node operation failed.", e);
            }
        });
        String message = messageBuffer.toString();
        return Strings.isNullOrEmpty(message) ? Result.successResult() : Result.failResult().setMessage(message);
    }

    interface ClusterHandler {

        boolean handle(Cluster cluster, RedisNode redisNode);

    }

    interface NodeHandler {

        boolean handle(Cluster cluster, RedisNode redisNode, AbstractNodeOperation abstractNodeOperation);

    }

}
