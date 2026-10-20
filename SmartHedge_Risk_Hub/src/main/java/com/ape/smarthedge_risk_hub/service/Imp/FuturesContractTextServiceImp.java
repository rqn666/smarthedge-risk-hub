package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.dao.ExchangeMapper;
import com.ape.smarthedge_risk_hub.dao.FuturesContractTextMapper;
import com.ape.smarthedge_risk_hub.dao.IndustryMapper;
import com.ape.smarthedge_risk_hub.dao.TradingVarietyMapper;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.sync.EsWriteHelper;
import com.ape.smarthedge_risk_hub.pojo.*;
import com.ape.smarthedge_risk_hub.service.IFuturesContractTextService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Service
public class FuturesContractTextServiceImp extends ServiceImpl<FuturesContractTextMapper, FuturesContractText> implements IFuturesContractTextService {
    @Autowired
    FuturesContractTextMapper futuresContractTextMapper;
    @Autowired
    TradingVarietyMapper tradingVarietyMapper;
    @Autowired
    ExchangeMapper exchangeMapper;
    @Autowired
    IndustryMapper industryMapper;

    /** ES 双写：写 MySQL 成功后同步到 ES，失败落重试表 */
    @Autowired
    EsWriteHelper esWriteHelper;

    @Override
    public Page<FuturesContractText> findByPage(RequestParam requestParam) {
        Page<FuturesContractText> page = new Page(requestParam.getPage(), requestParam.getSize());
        return futuresContractTextMapper.findByPage(page, requestParam.getKeyword());
    }

    @Override
    public int insertText(FuturesContractTextAddDTO futuresContractTextAddDTO) {
        LambdaQueryWrapper<TradingVariety> tradingVarietyWrapper = Wrappers.lambdaQuery();
        tradingVarietyWrapper.eq(TradingVariety::getVarietyName, futuresContractTextAddDTO.getVarietyName()).eq(TradingVariety::getVarietyCode, futuresContractTextAddDTO.getVarietyCode());
        TradingVariety tradingVariety = tradingVarietyMapper.selectOne(tradingVarietyWrapper);
        LambdaQueryWrapper<Exchange> exchangWrapper = Wrappers.lambdaQuery();
        exchangWrapper.eq(Exchange::getExchangeName, futuresContractTextAddDTO.getExchangeName());
        Exchange exchange = exchangeMapper.selectOne(exchangWrapper);
        LambdaQueryWrapper<Industry> industryWrapper = Wrappers.lambdaQuery();
        industryWrapper.eq(Industry::getIndustryName, futuresContractTextAddDTO.getIndustryName());
        FuturesContractText futuresContractText = new FuturesContractText();
        futuresContractText.setContractId(futuresContractTextAddDTO.getContractId());
        futuresContractText.setVarietyId(tradingVariety.getVarietyId());
        futuresContractText.setExchangeId(exchange.getExchangeId());
        futuresContractText.setMinPriceMove(futuresContractTextAddDTO.getMinPriceMove());
        futuresContractText.setTradeUnit(futuresContractTextAddDTO.getTradeUnit());
        futuresContractText.setQuoteUnit(futuresContractTextAddDTO.getQuoteUnit());
        futuresContractText.setMinMargin(futuresContractTextAddDTO.getMinMargin());
        futuresContractText.setLimitRange(futuresContractTextAddDTO.getLimitRange());
        futuresContractText.setContractMonths(futuresContractTextAddDTO.getContractMonths());
        futuresContractText.setTradeTime(futuresContractTextAddDTO.getTradeTime());
        futuresContractText.setLastTradingDayRule(futuresContractTextAddDTO.getLastTradingDayRule());
        futuresContractText.setDeliveryDateRule(futuresContractTextAddDTO.getDeliveryDateRule());
        futuresContractText.setMinMargin(futuresContractTextAddDTO.getMinMargin());
        int rows = futuresContractTextMapper.insertText(futuresContractText);
        if (rows > 0) {
            // 把联表查到的名称塞进对象，ES 文档才能带上"螺纹钢""上期所"这些可搜字段
            futuresContractText.setTradingVariety(tradingVariety);
            futuresContractText.setExchange(exchange);
            esWriteHelper.upsertAfterCommit(EsIndex.FUTURES_CONTRACT_TEXT, futuresContractText);
        }
        return rows;
    }

    @Override
    public int updateText(FuturesContractTextAddDTO futuresContractTextAddDTO, int id) {
        LambdaQueryWrapper<TradingVariety> tradingVarietyWrapper = Wrappers.lambdaQuery();
        tradingVarietyWrapper.eq(TradingVariety::getVarietyName, futuresContractTextAddDTO.getVarietyName()).eq(TradingVariety::getVarietyCode, futuresContractTextAddDTO.getVarietyCode());
        TradingVariety tradingVariety = tradingVarietyMapper.selectOne(tradingVarietyWrapper);
        LambdaQueryWrapper<Exchange> exchangWrapper = Wrappers.lambdaQuery();
        exchangWrapper.eq(Exchange::getExchangeName, futuresContractTextAddDTO.getExchangeName());
        Exchange exchange = exchangeMapper.selectOne(exchangWrapper);
        LambdaQueryWrapper<Industry> industryWrapper = Wrappers.lambdaQuery();
        industryWrapper.eq(Industry::getIndustryName, futuresContractTextAddDTO.getIndustryName());
        FuturesContractText futuresContractText = new FuturesContractText();
        futuresContractText.setTextId(id);
        futuresContractText.setContractId(futuresContractTextAddDTO.getContractId());
        futuresContractText.setVarietyId(tradingVariety.getVarietyId());
        futuresContractText.setExchangeId(exchange.getExchangeId());
        futuresContractText.setMinPriceMove(futuresContractTextAddDTO.getMinPriceMove());
        futuresContractText.setTradeUnit(futuresContractTextAddDTO.getTradeUnit());
        futuresContractText.setQuoteUnit(futuresContractTextAddDTO.getQuoteUnit());
        futuresContractText.setMinMargin(futuresContractTextAddDTO.getMinMargin());
        futuresContractText.setLimitRange(futuresContractTextAddDTO.getLimitRange());
        futuresContractText.setContractMonths(futuresContractTextAddDTO.getContractMonths());
        futuresContractText.setTradeTime(futuresContractTextAddDTO.getTradeTime());
        futuresContractText.setLastTradingDayRule(futuresContractTextAddDTO.getLastTradingDayRule());
        futuresContractText.setDeliveryDateRule(futuresContractTextAddDTO.getDeliveryDateRule());
        int rows = futuresContractTextMapper.updateText(futuresContractText);
        if (rows > 0) {
            futuresContractText.setTradingVariety(tradingVariety);
            futuresContractText.setExchange(exchange);
            esWriteHelper.upsertAfterCommit(EsIndex.FUTURES_CONTRACT_TEXT, futuresContractText);
        }
        return rows;
    }

    /**
     * 覆盖父类 removeById，保证删除时同步清 ES 文档。
     * Controller 走的是 ServiceImpl.removeById(textId)，不重写就会漏删。
     */
    @Override
    public boolean removeById(java.io.Serializable id) {
        boolean removed = super.removeById(id);
        if (removed) {
            esWriteHelper.deleteAfterCommit(EsIndex.FUTURES_CONTRACT_TEXT, id);
        }
        return removed;
    }
}
