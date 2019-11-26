package com.dragon.flow.service.flowable;

import com.dragon.flow.vo.flowable.ProcessDefinitionQueryVo;
import com.dragon.flow.vo.flowable.ret.ProcessDefinitionVo;
import com.dragon.tools.pager.PagerModel;
import com.dragon.tools.pager.Query;
import com.github.pagehelper.Page;
import org.flowable.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.flowable.engine.impl.persistence.entity.ProcessDefinitionEntityImpl;

/**
 * @author : bruce.liu
 * @title: : IFlowProcessDi
 * @projectName : flowable
 * @description: 流程定义
 * @date : 2019/11/1314:11
 */
public interface IFlowableProcessDefinitionService {

    /**
     * 通过条件查询流程定义
     * @param params
     * @return
     */
    public PagerModel<ProcessDefinitionVo> getPagerModel(ProcessDefinitionQueryVo params,Query query) ;

}
