package com.ape.smarthedge_risk_hub.service.Imp;

import com.ape.smarthedge_risk_hub.common.RequestParam;
import com.ape.smarthedge_risk_hub.dao.ExchangeMapper;
import com.ape.smarthedge_risk_hub.dao.IndustryMapper;
import com.ape.smarthedge_risk_hub.dao.OptionsContractTextMapper;
import com.ape.smarthedge_risk_hub.dao.TradingVarietyMapper;
import com.ape.smarthedge_risk_hub.es.constant.EsIndex;
import com.ape.smarthedge_risk_hub.es.sync.EsWriteHelper;
import com.ape.smarthedge_risk_hub.pojo.*;
import com.ape.smarthedge_risk_hub.service.IOptionContractTextService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OptionContractTextServiceImp extends ServiceImpl<OptionsContractTextMapper, OptionsContractText> implements IOptionContractTextService {
    @Autowired
    OptionsContractTextMapper optionsContractTextMapper;
    @Autowired
    TradingVarietyMapper tradingVarietyMapper;
    @Autowired
    ExchangeMapper exchangeMapper;
    @Autowired
    IndustryMapper industryMapper;

    /** ES 双写 */
    @Autowired
    EsWriteHelper esWriteHelper;

    @Override
    public Page<OptionsContractText> findByPage(RequestParam requestParam) {
        Page<OptionsContractText> optionsContractTextPage = new Page<>(requestParam.getPage(), requestParam.getSize());
        return optionsContractTextMapper.findByPage(optionsContractTextPage, requestParam.getKeyword());

    }

    @Override
    public int insertText(OptionsContractTextAddDTO optionsContractTextAddDTO) {
        LambdaQueryWrapper<TradingVariety> varietyLambdaQueryWrapper = new LambdaQueryWrapper<>();
        varietyLambdaQueryWrapper.eq(TradingVariety::getVarietyName, optionsContractTextAddDTO.getVarietyName());
        TradingVariety tradingVariety = tradingVarietyMapper.selectOne(varietyLambdaQueryWrapper);
        LambdaQueryWrapper<Exchange> exchangeLambdaQueryWrapper = new LambdaQueryWrapper<>();
        exchangeLambdaQueryWrapper.eq(Exchange::getExchangeName, optionsContractTextAddDTO.getExchangeName());
        Exchange exchange = exchangeMapper.selectOne(exchangeLambdaQueryWrapper);
        OptionsContractText optionsContractText = new OptionsContractText();
        optionsContractText.setOptionId(optionsContractTextAddDTO.getOptionId());
        optionsContractText.setVarietyId(tradingVariety.getVarietyId());
        optionsContractText.setExchangeId(exchange.getExchangeId());
        optionsContractText.setUnderlyingAsset(optionsContractTextAddDTO.getUnderlyingAsset());
        optionsContractText.setOptionType(optionsContractTextAddDTO.getOptionType());
        optionsContractText.setTradeUnit(optionsContractTextAddDTO.getTradeUnit());
        optionsContractText.setQuoteUnit(optionsContractTextAddDTO.getQuoteUnit());
        optionsContractText.setMinPriceMove(optionsContractTextAddDTO.getMinPriceMove());
        optionsContractText.setLimitRange(optionsContractTextAddDTO.getLimitRange());
        optionsContractText.setContractMonths(optionsContractTextAddDTO.getContractMonths());
        optionsContractText.setTradeTime(optionsContractTextAddDTO.getTradeTime());
        optionsContractText.setLastTradingDayRule(optionsContractTextAddDTO.getLastTradingDayRule());
        optionsContractText.setExpiryDateRule(optionsContractTextAddDTO.getExpiryDateRule());
        optionsContractText.setStrikePriceRule(optionsContractTextAddDTO.getStrikePriceRule());
        optionsContractText.setExerciseMethod(optionsContractTextAddDTO.getExerciseMethod());
        optionsContractText.setTradeCode(optionsContractTextAddDTO.getTradeCode());
        int rows = optionsContractTextMapper.insertText(optionsContractText);
        if (rows > 0) {
            optionsContractText.setTradingVariety(tradingVariety);
            optionsContractText.setExchange(exchange);
            esWriteHelper.upsertAfterCommit(EsIndex.OPTIONS_CONTRACT_TEXT, optionsContractText);
        }
        return rows;
    }

    @Override
    public int updateText(OptionsContractTextAddDTO optionsContractTextAddDTO, int id) {
        LambdaQueryWrapper<TradingVariety> varietyLambdaQueryWrapper = new LambdaQueryWrapper<>();
        varietyLambdaQueryWrapper.eq(TradingVariety::getVarietyName, optionsContractTextAddDTO.getVarietyName());
        TradingVariety tradingVariety = tradingVarietyMapper.selectOne(varietyLambdaQueryWrapper);
        LambdaQueryWrapper<Exchange> exchangeLambdaQueryWrapper = new LambdaQueryWrapper<>();
        exchangeLambdaQueryWrapper.eq(Exchange::getExchangeName, optionsContractTextAddDTO.getExchangeName());
        Exchange exchange = exchangeMapper.selectOne(exchangeLambdaQueryWrapper);
        OptionsContractText optionsContractText = new OptionsContractText();
        optionsContractText.setTextId(id);
        optionsContractText.setOptionId(optionsContractTextAddDTO.getOptionId());
        optionsContractText.setVarietyId(tradingVariety.getVarietyId());
        optionsContractText.setExchangeId(exchange.getExchangeId());
        optionsContractText.setUnderlyingAsset(optionsContractTextAddDTO.getUnderlyingAsset());
        optionsContractText.setOptionType(optionsContractTextAddDTO.getOptionType());
        optionsContractText.setTradeUnit(optionsContractTextAddDTO.getTradeUnit());
        optionsContractText.setQuoteUnit(optionsContractTextAddDTO.getQuoteUnit());
        optionsContractText.setMinPriceMove(optionsContractTextAddDTO.getMinPriceMove());
        optionsContractText.setLimitRange(optionsContractTextAddDTO.getLimitRange());
        optionsContractText.setContractMonths(optionsContractTextAddDTO.getContractMonths());
        optionsContractText.setTradeTime(optionsContractTextAddDTO.getTradeTime());
        optionsContractText.setLastTradingDayRule(optionsContractTextAddDTO.getLastTradingDayRule());
        optionsContractText.setExpiryDateRule(optionsContractTextAddDTO.getExpiryDateRule());
        optionsContractText.setStrikePriceRule(optionsContractTextAddDTO.getStrikePriceRule());
        optionsContractText.setExerciseMethod(optionsContractTextAddDTO.getExerciseMethod());
        optionsContractText.setTradeCode(optionsContractTextAddDTO.getTradeCode());
        int rows = optionsContractTextMapper.updateText(optionsContractText);
        if (rows > 0) {
            optionsContractText.setTradingVariety(tradingVariety);
            optionsContractText.setExchange(exchange);
            esWriteHelper.upsertAfterCommit(EsIndex.OPTIONS_CONTRACT_TEXT, optionsContractText);
        }
        return rows;
    }

    /** 覆盖父类 removeById，删除时同步清 ES 文档 */
    @Override
    public boolean removeById(java.io.Serializable id) {
        boolean removed = super.removeById(id);
        if (removed) {
            esWriteHelper.deleteAfterCommit(EsIndex.OPTIONS_CONTRACT_TEXT, id);
        }
        return removed;
    }
}
