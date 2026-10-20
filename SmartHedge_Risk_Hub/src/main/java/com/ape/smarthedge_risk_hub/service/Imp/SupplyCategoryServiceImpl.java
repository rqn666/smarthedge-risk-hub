package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.dao.SupplyCategoryMapper;
import com.ape.smarthedge_risk_hub.pojo.SupplyCategory;
import com.ape.smarthedge_risk_hub.service.ISupplyCategoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SupplyCategoryServiceImpl extends ServiceImpl<SupplyCategoryMapper, SupplyCategory> implements ISupplyCategoryService {
}
