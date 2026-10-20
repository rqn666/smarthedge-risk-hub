package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.dao.OperatorMapper;
import com.ape.smarthedge_risk_hub.pojo.Operator;
import com.ape.smarthedge_risk_hub.service.IOperatorService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class OperatorServiceImpl extends ServiceImpl<OperatorMapper, Operator> implements IOperatorService {
}
