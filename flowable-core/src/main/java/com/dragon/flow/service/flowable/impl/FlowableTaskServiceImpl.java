package com.dragon.flow.service.flowable.impl;

import com.dragon.flow.constant.FlowConstant;
import com.dragon.flow.dao.flowable.IFlowableTaskDao;
import com.dragon.flow.enm.flowable.CommentTypeEnum;
import com.dragon.flow.service.flowable.IFlowableBpmnModelService;
import com.dragon.flow.service.flowable.IFlowableTaskService;
import com.dragon.flow.vo.flowable.*;
import com.dragon.flow.vo.flowable.ret.FlowNodeVo;
import com.dragon.flow.vo.flowable.ret.TaskVo;
import com.dragon.tools.common.ReturnCode;
import com.dragon.tools.pager.PagerModel;
import com.dragon.tools.pager.Query;
import com.dragon.tools.vo.ReturnVo;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.editor.language.json.converter.util.CollectionUtils;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ActivityInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExecutionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.idm.api.User;
import org.flowable.idm.api.UserQuery;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.flowable.task.service.impl.persistence.entity.TaskEntityImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author : bruce.liu
 * @title: : FlowableTaskServiceImpl
 * @projectName : flowable
 * @description: task service
 * @date : 2019/11/1315:15
 */
@Service
public class FlowableTaskServiceImpl extends BaseProcessService implements IFlowableTaskService {

    @Autowired
    private IFlowableTaskDao flowableTaskDao;
    @Autowired
    private IFlowableBpmnModelService flowableBpmnModelService;

    @Override
    public boolean checkParallelgatewayNode(String taskId) {
        boolean flag = false;
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        String executionId = task.getExecutionId();
        Execution execution = runtimeService.createExecutionQuery().executionId(executionId).singleResult();
        String pExecutionId = execution.getParentId();
        Execution pExecution = runtimeService.createExecutionQuery().executionId(pExecutionId).singleResult();
        if (pExecution != null) {
            String ppExecutionId = pExecution.getParentId();
            long count = runtimeService.createExecutionQuery().executionId(ppExecutionId).count();
            if (count == 0) {
                flag = true;
            }
        }
        return flag;
    }

    @Override
    public ReturnVo<String> backToStepTask(BackTaskVo backTaskVo) {
        ReturnVo<String> returnVo = null;
        TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(backTaskVo.getTaskId()).singleResult();
        //1.??????????????????????????????
        if (taskEntity != null) {
            //2.???????????????
            taskEntity.setAssignee(backTaskVo.getUserCode());
            taskService.saveTask(taskEntity);
            //3.??????????????????
            this.addComment(backTaskVo.getTaskId(), backTaskVo.getUserCode(), backTaskVo.getProcessInstanceId(),
                    CommentTypeEnum.BH.toString(), backTaskVo.getMessage());
            //4.?????????????????????
            FlowNode distActivity = flowableBpmnModelService.findFlowNodeByActivityId(taskEntity.getProcessDefinitionId(), backTaskVo.getDistFlowElementId());
            if (distActivity != null) {
                if (FlowConstant.FLOW_SUBMITTER.equals(distActivity.getName())) {
                    ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(taskEntity.getProcessInstanceId()).singleResult();
                    runtimeService.setVariable(backTaskVo.getProcessInstanceId(), FlowConstant.FLOW_SUBMITTER_VAR, processInstance.getStartUserId());
                }
            }
            //5.????????????
            this.deleteActivity(backTaskVo.getDistFlowElementId(), taskEntity.getProcessInstanceId());
            List<String> executionIds = new ArrayList<>();
            //6.?????????????????????????????????????????????
            if (flowableBpmnModelService.checkActivitySubprocessByActivityId(taskEntity.getProcessDefinitionId(),
                    backTaskVo.getDistFlowElementId())
                    && flowableBpmnModelService.checkActivitySubprocessByActivityId(taskEntity.getProcessDefinitionId(),
                    taskEntity.getTaskDefinitionKey())) {
                //6.1 ?????????????????????
                Execution executionTask = runtimeService.createExecutionQuery().executionId(taskEntity.getExecutionId()).singleResult();
                String parentId = executionTask.getParentId();
                List<Execution> executions = runtimeService.createExecutionQuery().parentId(parentId).list();
                executions.forEach(execution -> executionIds.add(execution.getId()));
                this.moveExecutionsToSingleActivityId(executionIds, backTaskVo.getDistFlowElementId());
            } else {
                //6.2 ????????????
                List<Execution> executions = runtimeService.createExecutionQuery().parentId(taskEntity.getProcessInstanceId()).list();
                executions.forEach(execution -> executionIds.add(execution.getId()));
                this.moveExecutionsToSingleActivityId(executionIds, backTaskVo.getDistFlowElementId());
            }
            returnVo = new ReturnVo<>(ReturnCode.SUCCESS, "????????????!");
        } else {
            returnVo = new ReturnVo<>(ReturnCode.FAIL, "?????????????????????,?????????!");
        }
        return returnVo;
    }

    @Override
    public List<FlowNodeVo> getBackNodesByProcessInstanceId(String processInstanceId, String taskId) {
        List<FlowNodeVo> backNods = new ArrayList<>();
        TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(taskId).singleResult();
        String currActId = taskEntity.getTaskDefinitionKey();
        //????????????????????????usertask
        String sql = "select t.* from act_ru_actinst t where t.ACT_TYPE_ = 'userTask' " +
                " and t.PROC_INST_ID_=#{processInstanceId} and t.END_TIME_ is not null ";
        List<ActivityInstance> activityInstances = runtimeService.createNativeActivityInstanceQuery().sql(sql)
                .parameter("processInstanceId", processInstanceId)
                .list();
        //????????????????????????parallelGateway???????????????
        sql = "SELECT t.ID_, t.REV_,t.PROC_DEF_ID_,t.PROC_INST_ID_,t.EXECUTION_ID_,t.ACT_ID_, t.TASK_ID_, t.CALL_PROC_INST_ID_, t.ACT_NAME_, t.ACT_TYPE_, " +
                " t.ASSIGNEE_, t.START_TIME_, max(t.END_TIME_) as END_TIME_, t.DURATION_, t.DELETE_REASON_, t.TENANT_ID_" +
                " FROM  act_ru_actinst t WHERE t.ACT_TYPE_ = 'parallelGateway' AND t.PROC_INST_ID_ = #{processInstanceId} and t.END_TIME_ is not null" +
                " and t.ACT_ID_ <> #{actId} GROUP BY t.act_id_";
        List<ActivityInstance> parallelGatewaies = runtimeService.createNativeActivityInstanceQuery().sql(sql)
                .parameter("processInstanceId", processInstanceId)
                .parameter("actId", currActId)
                .list();
        //??????
        if (CollectionUtils.isNotEmpty(parallelGatewaies)) {
            activityInstances.addAll(parallelGatewaies);
            activityInstances.sort(Comparator.comparing(ActivityInstance::getEndTime));
        }
        //????????????
        int count = 0;
        Map<ActivityInstance, List<ActivityInstance>> parallelGatewayUserTasks = new HashMap<>();
        List<ActivityInstance> userTasks = new ArrayList<>();
        ActivityInstance currActivityInstance = null;
        for (ActivityInstance activityInstance : activityInstances) {
            if (BpmnXMLConstants.ELEMENT_GATEWAY_PARALLEL.equals(activityInstance.getActivityType())) {
                count++;
                if (count % 2 != 0) {
                    List<ActivityInstance> datas = new ArrayList<>();
                    currActivityInstance = activityInstance;
                    parallelGatewayUserTasks.put(currActivityInstance, datas);
                }
            }
            if (BpmnXMLConstants.ELEMENT_TASK_USER.equals(activityInstance.getActivityType())) {
                if (count % 2 == 0) {
                    userTasks.add(activityInstance);
                } else {
                    if (parallelGatewayUserTasks.containsKey(currActivityInstance)) {
                        parallelGatewayUserTasks.get(currActivityInstance).add(activityInstance);
                    }
                }
            }
        }
        //??????????????????
        List<HistoricTaskInstance> historicTaskInstances = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).finished().list();
        Map<String, List<HistoricTaskInstance>> taskInstanceMap = new HashMap<>();
        List<String> userCodes = new ArrayList<>();
        historicTaskInstances.forEach(historicTaskInstance -> {
            userCodes.add(historicTaskInstance.getAssignee());
            String taskDefinitionKey = historicTaskInstance.getTaskDefinitionKey();
            if (taskInstanceMap.containsKey(historicTaskInstance.getTaskDefinitionKey())) {
                taskInstanceMap.get(taskDefinitionKey).add(historicTaskInstance);
            } else {
                List<HistoricTaskInstance> tasks = new ArrayList<>();
                tasks.add(historicTaskInstance);
                taskInstanceMap.put(taskDefinitionKey, tasks);
            }
        });
        //??????usertask?????????
        List<User> userList = identityService.createUserQuery().userIds(userCodes).list();
        Map<String, String> activityIdUserNames = this.getApplyers(processInstanceId, userList, taskInstanceMap);
        if (CollectionUtils.isNotEmpty(userTasks)) {
            userTasks.forEach(activityInstance -> {
                FlowNodeVo node = new FlowNodeVo();
                node.setNodeId(activityInstance.getActivityId());
                node.setNodeName(activityInstance.getActivityName());
                node.setEndTime(activityInstance.getEndTime());
                node.setUserName(activityIdUserNames.get(activityInstance.getActivityId()));
                backNods.add(node);
            });
        }
        //????????????????????????
        if (MapUtils.isNotEmpty(taskInstanceMap)) {
            parallelGatewayUserTasks.forEach((activity, activities) -> {
                FlowNodeVo node = new FlowNodeVo();
                node.setNodeId(activity.getActivityId());
                node.setEndTime(activity.getEndTime());
                StringBuffer nodeNames = new StringBuffer("??????:");
                StringBuffer userNames = new StringBuffer("????????????:");
                if (CollectionUtils.isNotEmpty(activities)) {
                    activities.forEach(activityInstance -> {
                        nodeNames.append(activityInstance.getActivityName()).append(",");
                        userNames.append(activityIdUserNames.get(activityInstance.getActivityId())).append(",");
                    });
                    node.setNodeName(nodeNames.toString());
                    node.setUserName(userNames.toString());
                    backNods.add(node);
                }
            });
        }
        //????????????
        List<FlowNodeVo> datas = backNods.stream().collect(
                Collectors.collectingAndThen(Collectors.toCollection(() ->
                        new TreeSet<>(Comparator.comparing(nodeVo -> nodeVo.getNodeId()))), ArrayList::new));

        //??????
        datas.sort(Comparator.comparing(FlowNodeVo::getEndTime));
        return datas;
    }

    private Map<String, String> getApplyers(String processInstanceId, List<User> userList, Map<String, List<HistoricTaskInstance>> taskInstanceMap) {
        Map<String, User> userMap = userList.stream().collect(Collectors.toMap(User::getId, user -> user));
        Map<String, String> applyMap = new HashMap<>();
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        taskInstanceMap.forEach((activityId, taskInstances) -> {
            StringBuffer applyers = new StringBuffer();
            StringBuffer finalApplyers = applyers;
            taskInstances.forEach(taskInstance -> {
                if (!taskInstance.getName().equals(FlowConstant.FLOW_SUBMITTER)) {
                    User user = userMap.get(taskInstance.getAssignee());
                    if (user != null) {
                        if (StringUtils.indexOf(finalApplyers.toString(), user.getDisplayName()) == -1) {
                            finalApplyers.append(user.getDisplayName()).append(",");
                        }
                    }
                } else {
                    String startUserId = processInstance.getStartUserId();
                    User user = identityService.createUserQuery().userId(startUserId).singleResult();
                    if (user != null) {
                        finalApplyers.append(user.getDisplayName()).append(",");
                    }
                }
            });
            if (applyers.length() > 0) {
                applyers = applyers.deleteCharAt(applyers.length() - 1);
            }
            applyMap.put(activityId, applyers.toString());
        });
        return applyMap;
    }

    @Override
    public ReturnVo<String> beforeAddSignTask(AddSignTaskVo addSignTaskVo) {
        return this.addSignTask(addSignTaskVo, false);
    }

    @Override
    public ReturnVo<String> afterAddSignTask(AddSignTaskVo addSignTaskVo) {
        return this.addSignTask(addSignTaskVo, true);
    }

    @Override
    public ReturnVo<String> addSignTask(AddSignTaskVo addSignTaskVo, Boolean flag) {
        ReturnVo<String> returnVo = null;
        TaskEntityImpl taskEntity = (TaskEntityImpl) taskService.createTaskQuery().taskId(addSignTaskVo.getTaskId()).singleResult();
        //1.??????????????????????????????
        if (taskEntity != null) {
            //????????????????????????
            String parentTaskId = taskEntity.getParentTaskId();
            if (StringUtils.isBlank(parentTaskId)) {
                taskEntity.setOwner(addSignTaskVo.getUserCode());
                taskEntity.setAssignee(null);
                taskEntity.setCountEnabled(true);
                if (flag) {
                    taskEntity.setScopeType(FlowConstant.AFTER_ADDSIGN);
                } else {
                    taskEntity.setScopeType(FlowConstant.BEFORE_ADDSIGN);
                }
                //1.2 ???????????????????????????
                taskService.saveTask(taskEntity);
            }
            //2.??????????????????
            this.createSignSubTasks(addSignTaskVo, taskEntity);
            //3.??????????????????
            String type = flag ? CommentTypeEnum.HJQ.toString() : CommentTypeEnum.QJQ.toString();
            this.addComment(addSignTaskVo.getTaskId(), addSignTaskVo.getUserCode(), addSignTaskVo.getProcessInstanceId(),
                    type, addSignTaskVo.getMessage());
            String message = flag ? "???????????????" : "???????????????";
            returnVo = new ReturnVo<>(ReturnCode.SUCCESS, message);
        } else {
            returnVo = new ReturnVo<>(ReturnCode.FAIL, "?????????????????????????????????!");
        }
        return returnVo;
    }

    /**
     * ?????????????????????
     *
     * @param signVo     ????????????
     * @param taskEntity ?????????
     */
    private void createSignSubTasks(AddSignTaskVo signVo, TaskEntity taskEntity) {
        if (CollectionUtils.isNotEmpty(signVo.getSignPersoneds())) {
            String parentTaskId = taskEntity.getParentTaskId();
            if (StringUtils.isBlank(parentTaskId)) {
                parentTaskId = taskEntity.getId();
            }
            String finalParentTaskId = parentTaskId;
            //1.?????????????????????????????????
            signVo.getSignPersoneds().forEach(userCode -> {
                if (StringUtils.isNotBlank(userCode)) {
                    this.createSubTask(taskEntity, finalParentTaskId, userCode);
                }
            });
            String taskId = taskEntity.getId();
            if (StringUtils.isBlank(taskEntity.getParentTaskId())) {
                //2.???????????????????????????????????????
                Task task = this.createSubTask(taskEntity, finalParentTaskId, signVo.getUserCode());
                taskId = task.getId();
            }
            Task taskInfo = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (null != taskInfo) {
                taskService.complete(taskId);
            }
            //??????????????????????????????????????????????????????????????????
            long candidateCount = taskService.createTaskQuery().taskId(parentTaskId).taskCandidateUser(signVo.getUserCode()).count();
            if (candidateCount > 0) {
                taskService.deleteCandidateUser(parentTaskId, signVo.getUserCode());
            }
        }
    }

    @Override
    public ReturnVo<String> unClaimTask(ClaimTaskVo claimTaskVo) {
        ReturnVo<String> returnVo = null;
        TaskEntityImpl currTask = (TaskEntityImpl) taskService.createTaskQuery().taskId(claimTaskVo.getTaskId()).singleResult();
        if (currTask != null) {
            //1.??????????????????
            this.addComment(claimTaskVo.getTaskId(), claimTaskVo.getProcessInstanceId(), CommentTypeEnum.QS.toString(), claimTaskVo.getMessage());
            List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(claimTaskVo.getTaskId());
            boolean flag = false;
            if (CollectionUtils.isNotEmpty(identityLinks)) {
                for (IdentityLink link : identityLinks) {
                    if (IdentityLinkType.CANDIDATE.equals(link.getType())) {
                        flag = true;
                        break;
                    }
                }
            }
            //2.?????????
            if (flag) {
                taskService.claim(claimTaskVo.getTaskId(), null);
                returnVo = new ReturnVo<>(ReturnCode.SUCCESS, "???????????????");
            } else {
                returnVo = new ReturnVo<>(ReturnCode.FAIL, "?????????????????????????????????,???????????????????????????,?????????.");
            }
        } else {
            returnVo = new ReturnVo<>(ReturnCode.FAIL, "???????????????");
        }
        return returnVo;
    }

    @Override
    public ReturnVo<String> claimTask(ClaimTaskVo claimTaskVo) {
        ReturnVo<String> returnVo = null;
        TaskEntityImpl currTask = (TaskEntityImpl) taskService.createTaskQuery().taskId(claimTaskVo.getTaskId()).singleResult();
        if (currTask != null) {
            //1.??????????????????
            this.addComment(claimTaskVo.getTaskId(), claimTaskVo.getProcessInstanceId(), CommentTypeEnum.QS.toString(), claimTaskVo.getMessage());
            //2.??????
            taskService.claim(claimTaskVo.getTaskId(), claimTaskVo.getUserCode());
            returnVo = new ReturnVo<>(ReturnCode.SUCCESS, "????????????");
        } else {
            returnVo = new ReturnVo<>(ReturnCode.FAIL, "????????????");
        }
        return returnVo;
    }

    @Override
    public ReturnVo<String> delegateTask(DelegateTaskVo delegateTaskVo) {
        ReturnVo<String> returnVo = null;
        TaskEntityImpl currTask = (TaskEntityImpl) taskService.createTaskQuery().taskId(delegateTaskVo.getTaskId()).singleResult();
        if (currTask != null) {
            //1.??????????????????
            this.addComment(delegateTaskVo.getTaskId(), delegateTaskVo.getUserCode(), delegateTaskVo.getProcessInstanceId(), CommentTypeEnum.WP.toString(), delegateTaskVo.getMessage());
            //2.????????????????????????????????????
            taskService.setAssignee(delegateTaskVo.getTaskId(), delegateTaskVo.getUserCode());
            //3.????????????
            taskService.delegateTask(delegateTaskVo.getTaskId(), delegateTaskVo.getDelegateUserCode());
            returnVo = new ReturnVo<>(ReturnCode.SUCCESS, "????????????");
        } else {
            returnVo = new ReturnVo<>(ReturnCode.FAIL, "??????????????????????????????,?????????!");
        }
        return returnVo;
    }

    @Override
    public ReturnVo<String> turnTask(TurnTaskVo turnTaskVo) {
        ReturnVo<String> returnVo = null;
        TaskEntityImpl currTask = (TaskEntityImpl) taskService.createTaskQuery().taskId(turnTaskVo.getTaskId()).singleResult();
        if (currTask != null) {
            //1.??????????????????
            TaskEntity task = this.createSubTask(currTask, turnTaskVo.getUserCode());
            //2.??????????????????
            this.addComment(task.getId(), turnTaskVo.getUserCode(), turnTaskVo.getProcessInstanceId(), CommentTypeEnum.ZB.toString(), turnTaskVo.getMessage());
            taskService.complete(task.getId());
            //3.??????
            taskService.setAssignee(turnTaskVo.getTaskId(), turnTaskVo.getTurnToUserId());
            taskService.setOwner(turnTaskVo.getTaskId(), turnTaskVo.getUserCode());
            returnVo = new ReturnVo<>(ReturnCode.SUCCESS, "????????????");
        } else {
            returnVo = new ReturnVo<>(ReturnCode.FAIL, "??????????????????????????????,?????????!");
        }
        return returnVo;
    }

    @Override
    public ReturnVo<String> complete(CompleteTaskVo params) {
        ReturnVo<String> returnVo = new ReturnVo<>(ReturnCode.SUCCESS, "????????????");
        if (StringUtils.isNotBlank(params.getProcessInstanceId())
                && StringUtils.isNotBlank(params.getTaskId())) {
            //1.???????????????????????????
            TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().taskId(params.getTaskId()).singleResult();
            if (taskEntity != null) {
                String taskId = params.getTaskId();
                //2.????????????
                if (DelegationState.PENDING.equals(taskEntity.getDelegationState())) {
                    //2.1??????????????????
                    TaskEntity task = this.createSubTask(taskEntity, params.getUserCode());
                    taskService.complete(task.getId());
                    taskId = task.getId();
                    //2.2????????????
                    taskService.resolveTask(params.getTaskId(), params.getVariables());
                } else {
                    //3.1??????????????? ????????????????????????????????????
                    taskService.setAssignee(params.getTaskId(), params.getUserCode());
                    //3.2????????????
                    taskService.complete(params.getTaskId(), params.getVariables());
                    //4.?????????????????????
                    String parentTaskId = taskEntity.getParentTaskId();
                    if (StringUtils.isNotBlank(parentTaskId)) {
                        String tableName = managementService.getTableName(TaskEntity.class);
                        String sql = "select count(1) from " + tableName + " where PARENT_TASK_ID_=#{parentTaskId}";
                        long subTaskCount = taskService.createNativeTaskQuery().sql(sql).parameter("parentTaskId", parentTaskId).count();
                        if (subTaskCount == 0) {
                            Task task = taskService.createTaskQuery().taskId(parentTaskId).singleResult();
                            //???????????????????????????
                            taskService.resolveTask(parentTaskId);
                            if (FlowConstant.AFTER_ADDSIGN.equals(task.getScopeType())) {
                                taskService.complete(parentTaskId);
                            }
                        }
                    }
                }
                String type = params.getType() == null ? CommentTypeEnum.SP.toString() : params.getType();
                //5.??????????????????
                this.addComment(taskId, params.getUserCode(), params.getProcessInstanceId(), type, params.getMessage());
            } else {
                returnVo = new ReturnVo<>(ReturnCode.FAIL, "???????????????????????????!");
            }
        } else {
            returnVo = new ReturnVo<>(ReturnCode.FAIL, "????????????????????????!");
        }
        return returnVo;
    }

    @Override
    public ReturnVo<Task> findTaskById(String taskId) {
        ReturnVo<Task> returnVo = new ReturnVo<>(ReturnCode.SUCCESS, "OK");
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        returnVo.setData(task);
        return returnVo;
    }

    @Override
    public PagerModel<TaskVo> getApplyingTasks(TaskQueryVo params, Query query) {
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        Page<TaskVo> applyingTasks = flowableTaskDao.getApplyingTasks(params);
        return new PagerModel<>(applyingTasks);
    }

    @Override
    public PagerModel<TaskVo> getApplyedTasks(TaskQueryVo params, Query query) {
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        Page<TaskVo> applyedTasks = flowableTaskDao.getApplyedTasks(params);
        return new PagerModel<>(applyedTasks);
    }

    @Override
    public List<User> getApprovers(String processInstanceId) {
        List<User> users = new ArrayList<>();
        List<Task> list = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
        if (CollectionUtils.isNotEmpty(list)) {
            list.forEach(task -> {
                if (StringUtils.isNotBlank(task.getAssignee())) {
                    //1.?????????ASSIGNEE_?????????id
                    User user = identityService.createUserQuery().userId(task.getAssignee()).singleResult();
                    if (user != null) {
                        users.add(user);
                    }
                    //2.?????????ASSIGNEE_??????id
                    List<User> gusers = identityService.createUserQuery().memberOfGroup(task.getAssignee()).list();
                    if (CollectionUtils.isNotEmpty(gusers)) {
                        users.addAll(gusers);
                    }
                } else {
                    List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(task.getId());
                    if (CollectionUtils.isNotEmpty(identityLinks)) {
                        identityLinks.forEach(identityLink -> {
                            //3.?????????ASSIGNEE_??????,??????id
                            if (StringUtils.isNotBlank(identityLink.getUserId())) {
                                User user = identityService.createUserQuery().userId(identityLink.getUserId()).singleResult();
                                if (user != null) {
                                    users.add(user);
                                }
                            } else {
                                //4.?????????ASSIGNEE_??????,???id
                                List<User> gusers = identityService.createUserQuery().memberOfGroup(identityLink.getGroupId()).list();
                                if (CollectionUtils.isNotEmpty(gusers)) {
                                    users.addAll(gusers);
                                }
                            }
                        });
                    }
                }
            });
        }
        return users;
    }
}
