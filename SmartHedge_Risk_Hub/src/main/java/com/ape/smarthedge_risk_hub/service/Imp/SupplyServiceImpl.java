package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.dao.OperatorMapper;
import com.ape.smarthedge_risk_hub.dao.SupplierCategoryRelMapper;
import com.ape.smarthedge_risk_hub.dao.SupplierMapper;
import com.ape.smarthedge_risk_hub.dao.SupplyCategoryMapper;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.sync.EsWriteHelper;
import com.ape.smarthedge_risk_hub.pojo.*;
import com.ape.smarthedge_risk_hub.service.ISupplyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service

public class SupplyServiceImpl implements ISupplyService {
    @Autowired
    SupplierMapper supplierMapper;
    @Autowired
    OperatorMapper operatorMapper;
    @Autowired
    SupplyCategoryMapper supplyCategoryMapper;
    @Autowired
    SupplierCategoryRelMapper supplierCategoryRelMapper;

    /** ES 双写：供应商是事务方法，走 afterCommit 确保 MySQL 真提交后才写 ES */
    @Autowired
    EsWriteHelper esWriteHelper;

    @Override
    public IPage<Supplier> findAllSupplyByPage(RequestParam requestParam) {
        IPage<Supplier> page = new Page<>(requestParam.getPage(), requestParam.getSize());
        return supplierMapper.findAllSupplyByPage(page, requestParam.getKeyword());

    }

    @Transactional
    @Override
    public void addSupplierwithCategory(SupplierAddDTO supplierAddDTO) {
        LambdaQueryWrapper<Operator> operatorWrapper = Wrappers.lambdaQuery();
        operatorWrapper.eq(Operator::getOperatorName, supplierAddDTO.getOperatorName());
        Operator operator = operatorMapper.selectOne(operatorWrapper);
        if (operator == null) {
            throw new RuntimeException("经营者【" + supplierAddDTO.getOperatorName() + "】不存在");
        }
        LambdaQueryWrapper<SupplyCategory> categoryWrapper = Wrappers.lambdaQuery();
        categoryWrapper.in(SupplyCategory::getCategoryName, supplierAddDTO.getCategoryNames());
        List<SupplyCategory> supplyCategories = supplyCategoryMapper.selectList(categoryWrapper);
        Supplier supplier = new Supplier();
        supplier.setSupplierName(supplierAddDTO.getSupplierName());
        supplier.setContactPhone(supplierAddDTO.getContactPhone());
        supplier.setAddress(supplierAddDTO.getAddress());
        supplier.setCreditCode(supplierAddDTO.getCreditCode());
        supplier.setOperatorId(operator.getOperatorId());
        supplier.setScore(supplierAddDTO.getScore());
        supplierMapper.insertSupply(supplier);
        int supplierId = supplier.getSupplierId();
        for (SupplyCategory supplyCategory : supplyCategories) {
            SupplierCategoryRel supplierCategoryRel = new SupplierCategoryRel();
            supplierCategoryRel.setSupplierId(supplierId);
            supplierCategoryRel.setCategoryId(supplyCategory.getCategoryId());
            supplierCategoryRelMapper.insert(supplierCategoryRel);
        }
        // insertSupply 的 XML 配了 useGeneratedKeys，主键已回填
        supplier.setOperator(operator);
        esWriteHelper.upsertAfterCommit(EsIndex.SUPPLIER, supplier);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateSupplierwithCategory(SupplierAddDTO supplierAddDTO) {
        LambdaQueryWrapper<Operator> operatorWrapper = Wrappers.lambdaQuery();
        operatorWrapper.eq(Operator::getOperatorName, supplierAddDTO.getOperatorName());
        Operator operator = operatorMapper.selectOne(operatorWrapper);
        if (operator == null) {
            throw new RuntimeException("操作人不存在：" + supplierAddDTO.getOperatorName());
        }
        LambdaQueryWrapper<SupplyCategory> categoryWrapper = Wrappers.lambdaQuery();
        categoryWrapper.in(SupplyCategory::getCategoryName, supplierAddDTO.getCategoryNames());
        List<SupplyCategory> supplyCategories = supplyCategoryMapper.selectList(categoryWrapper);
        if (supplyCategories.isEmpty()) {
            throw new RuntimeException("所选分类不存在：" + supplierAddDTO.getCategoryNames());
        }
        Supplier supplier = supplierMapper.findById(supplierAddDTO.getId());
        if (supplier == null) {
            throw new RuntimeException("供应商不存在，ID：" + supplierAddDTO.getId());
        }
        supplier.setSupplierName(supplierAddDTO.getSupplierName());
        supplier.setContactPhone(supplierAddDTO.getContactPhone());
        supplier.setAddress(supplierAddDTO.getAddress());
        supplier.setCreditCode(supplierAddDTO.getCreditCode());
        supplier.setOperatorId(operator.getOperatorId());
        supplier.setStatus(supplierAddDTO.getStatus());
        supplier.setScore(supplierAddDTO.getScore());
        supplier.setUpdateTime(new Date());
        supplierMapper.updateSupplier(supplier);


        int supplierId = supplier.getSupplierId();
        LambdaQueryWrapper<SupplierCategoryRel> relWrapper = Wrappers.lambdaQuery();
        relWrapper.eq(SupplierCategoryRel::getSupplierId, supplierId);
        supplierCategoryRelMapper.delete(relWrapper);
        for (SupplyCategory supplyCategory : supplyCategories) {
            SupplierCategoryRel supplierCategoryRel = new SupplierCategoryRel();
            supplierCategoryRel.setSupplierId(supplierId);
            supplierCategoryRel.setCategoryId(supplyCategory.getCategoryId());
            supplierCategoryRelMapper.insert(supplierCategoryRel);
        }
        supplier.setOperator(operator);
        esWriteHelper.upsertAfterCommit(EsIndex.SUPPLIER, supplier);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(int id) {
        LambdaQueryWrapper<SupplierCategoryRel> relWrapper=Wrappers.lambdaQuery();
        relWrapper.eq(SupplierCategoryRel::getSupplierId,id);
        supplierCategoryRelMapper.delete(relWrapper);
        supplierMapper.deleteById(id);
        esWriteHelper.deleteAfterCommit(EsIndex.SUPPLIER, id);
    }
}
