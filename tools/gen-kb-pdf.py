#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成「SmartHedge 套期保值与风险管理制度手册」PDF —— RAG 知识库测试样本。

为什么用 reportlab + STSong-Light：
  中文 PDF 最大的坑是缺 CJK 字体，直接写中文会变成一堆黑块或报错。
  reportlab 内置 CID 字体 STSong-Light，无需外部字体文件，Windows 上开箱可用。

用法：
  python tools/gen-kb-pdf.py [输出路径]
"""
import sys
import os

from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak
)

FONT = "STSong-Light"
pdfmetrics.registerFont(UnicodeCIDFont(FONT))

BASE = getSampleStyleSheet()

TITLE = ParagraphStyle("title", parent=BASE["Title"], fontName=FONT, fontSize=22,
                       leading=30, alignment=TA_CENTER, spaceAfter=6)
SUBTITLE = ParagraphStyle("subtitle", parent=BASE["Normal"], fontName=FONT, fontSize=12,
                          leading=20, alignment=TA_CENTER, textColor=colors.HexColor("#555555"))
H1 = ParagraphStyle("h1", parent=BASE["Heading1"], fontName=FONT, fontSize=15, leading=24,
                    spaceBefore=14, spaceAfter=8, textColor=colors.HexColor("#1a4d7a"))
H2 = ParagraphStyle("h2", parent=BASE["Heading2"], fontName=FONT, fontSize=12.5, leading=20,
                    spaceBefore=10, spaceAfter=6, textColor=colors.HexColor("#264d73"))
BODY = ParagraphStyle("body", parent=BASE["Normal"], fontName=FONT, fontSize=10.5, leading=18,
                      alignment=TA_JUSTIFY, firstLineIndent=0, spaceAfter=4)
BULLET = ParagraphStyle("bullet", parent=BODY, leftIndent=14, bulletIndent=4, spaceAfter=3)
NOTE = ParagraphStyle("note", parent=BODY, fontSize=9.5, leading=16,
                      textColor=colors.HexColor("#666666"))


def para(t, s=BODY):
    return Paragraph(t, s)


def bullet(t):
    return Paragraph(t, BULLET, bulletText="•")


def table(rows, widths, header=True):
    tb = Table(rows, colWidths=widths, hAlign="LEFT")
    style = [
        ("FONTNAME", (0, 0), (-1, -1), FONT),
        ("FONTSIZE", (0, 0), (-1, -1), 9.5),
        ("LEADING", (0, 0), (-1, -1), 15),
        ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#b8c4d0")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
    ]
    if header:
        style += [
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#dce6f1")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor("#1a4d7a")),
        ]
    tb.setStyle(TableStyle(style))
    return tb


def build_story():
    s = []

    # ===== 封面 =====
    s.append(Spacer(1, 42 * mm))
    s.append(para("SmartHedge 套期保值与风险管理", TITLE))
    s.append(para("制 度 手 册", TITLE))
    s.append(Spacer(1, 10 * mm))
    s.append(para("SmartHedge Risk Hub · 内部知识库文档", SUBTITLE))
    s.append(Spacer(1, 20 * mm))
    s.append(table([
        ["文档编号", "SH-RM-2026-001"],
        ["版本号", "V3.2"],
        ["生效日期", "2026 年 1 月 1 日"],
        ["归口部门", "风险管理部"],
        ["密级", "内部资料 · 不得外传"],
        ["适用范围", "公司总部及各分支机构套期保值业务"],
    ], [35 * mm, 90 * mm], header=False))
    s.append(Spacer(1, 12 * mm))
    s.append(para("本手册为公司套期保值业务的操作依据，涵盖审批权限、保证金标准、"
                  "风险敞口控制、止损规则、供应商授信等内容。各部门应严格遵照执行。", NOTE))
    s.append(PageBreak())

    # ===== 一、总则 =====
    s.append(para("一、总则", H1))
    s.append(para("第一条　为规范公司大宗商品套期保值业务，防范价格波动风险，"
                  "确保期货、期权等衍生品交易服务于现货经营，特制定本手册。", BODY))
    s.append(para("第二条　公司开展套期保值业务遵循以下基本原则：", BODY))
    s.append(bullet("套保目的原则：套期保值以锁定现货价格风险为目的，<b>严禁以盈利为目的的投机交易</b>。"))
    s.append(bullet("品种对应原则：期货持仓品种必须与现货敞口品种一致，不得跨品种套保。"))
    s.append(bullet("数量匹配原则：期货持仓数量与现货敞口数量相匹配，套保比例控制在规定区间内。"))
    s.append(bullet("期限匹配原则：期货合约到期日应覆盖现货敞口的时间区间。"))
    s.append(para("第三条　本手册所称风险敞口，指公司持有的现货库存、已签订但未定价的购销合同，"
                  "以及未来确定发生的采购或销售需求所对应的价格风险。", BODY))

    # ===== 二、套期保值审批流程 =====
    s.append(para("二、套期保值审批权限与流程", H1))
    s.append(para("第四条　套期保值业务实行分级审批制度。依据单笔套保对应的现货敞口金额确定审批权限：", BODY))
    s.append(table([
        ["单笔敞口金额", "审批权限", "审批时限"],
        ["1000 万元以下（含）", "业务部门负责人审批，报风险管理部备案", "1 个工作日"],
        ["1000 万元至 5000 万元（含）", "风险管理委员会集体审批", "3 个工作日"],
        ["5000 万元以上", "报公司董事会审议批准", "10 个工作日"],
    ], [52 * mm, 78 * mm, 32 * mm]))
    s.append(Spacer(1, 4))
    s.append(para("第五条　审批流程节点依次为：业务部门提交申请 → 风险管理部风险评估 → "
                  "财务部资金安排确认 → 按金额分级审批 → 交易执行 → 台账登记。", BODY))
    s.append(para("第六条　<font color='#b30000'><b>单笔敞口超过 5000 万元的套保申请，"
                  "必须经董事会审议批准后方可执行</b></font>，未经批准擅自交易的，"
                  "按公司违规经营处理，相关责任人承担全部损失赔偿责任。", BODY))
    s.append(para("第七条　套期保值申请应载明：套保品种、方向、数量、拟用合约、"
                  "对应现货敞口、预计持有期限、止损安排。信息不全的，审批环节应予以退回。", BODY))

    # ===== 三、保证金标准 =====
    s.append(para("三、保证金收取标准与资金管理", H1))
    s.append(para("第八条　公司在交易所保证金标准基础上<b>统一加收 2 个百分点</b>作为内部风控缓冲。"
                  "各主要品种的内部保证金标准如下：", BODY))
    s.append(table([
        ["品种", "交易所标准", "公司内部标准", "备注"],
        ["螺纹钢", "6%", "8%", "黑色金属板块"],
        ["铁矿石", "8%", "10%", "黑色金属板块"],
        ["铜", "8%", "10%", "有色金属板块"],
        ["黄金", "4%", "6%", "贵金属板块"],
        ["原油", "10%", "12%", "能源化工板块"],
        ["豆粕", "7%", "9%", "农产品板块"],
    ], [30 * mm, 32 * mm, 34 * mm, 46 * mm]))
    s.append(Spacer(1, 4))
    s.append(para("第九条　保证金专用账户资金余额不得低于持仓保证金的 150%。"
                  "当风险度超过 80% 时，财务部应在 1 个交易日内追加保证金。", BODY))
    s.append(para("第十条　保证金占用情况由财务部每日核算，风险管理部每周复核，"
                  "月度经营分析会上通报。", BODY))

    # ===== 四、风险敞口与止损 =====
    s.append(para("四、风险敞口控制与止损规则", H1))
    s.append(para("第十一条　公司实行风险敞口限额管理：", BODY))
    s.append(table([
        ["限额类型", "限额标准", "监控频率"],
        ["单一品种持仓市值", "不超过公司净资产的 20%", "每日"],
        ["全部品种持仓市值合计", "不超过公司净资产的 50%", "每日"],
        ["套期保值比例下限", "风险敞口的 70%", "每旬"],
        ["套期保值比例上限", "风险敞口的 100%（禁止超额套保）", "每旬"],
    ], [52 * mm, 78 * mm, 32 * mm]))
    s.append(Spacer(1, 4))
    s.append(para("第十二条　<font color='#b30000'><b>止损规则（强制条款）</b></font>：", BODY))
    s.append(bullet("单品种浮动亏损达到该品种持仓保证金的 <b>5%</b> 时，"
                    "须在 1 个交易日内强制减仓至原持仓的 50%。"))
    s.append(bullet("单品种浮动亏损达到该品种持仓保证金的 <b>8%</b> 时，"
                    "须在 1 个交易日内<b>全部平仓</b>，不得拖延、不得对冲保留。"))
    s.append(bullet("触发止损后 3 个工作日内，风险管理部须提交书面原因分析报告。"))
    s.append(para("第十三条　出现以下情形之一的，应立即启动风险预警：", BODY))
    s.append(bullet("单一品种持仓市值占净资产比例超过 18%（接近 20% 上限）；"))
    s.append(bullet("某一交易日结算后保证金风险度超过 80%；"))
    s.append(bullet("某品种连续 3 个交易日累计浮亏超过持仓保证金的 4%。"))

    # ===== 五、供应商授信 =====
    s.append(para("五、供应商准入与授信管理", H1))
    s.append(para("第十四条　供应商实行信用评级管理，分为 A、B、C 三个等级，"
                  "评级依据包括：注册资本、财务报表、履约记录、涉诉情况、行业口碑。", BODY))
    s.append(table([
        ["信用等级", "评级标准", "授信额度上限", "结算方式"],
        ["A 级", "连续两年履约良好、无违约、财务稳健", "3000 万元", "可赊销，账期 60 天"],
        ["B 级", "履约记录正常、无重大涉诉", "1000 万元", "可赊销，账期 30 天"],
        ["C 级", "存在逾期交付、质量争议或涉诉记录", "不予授信", "款到发货"],
    ], [24 * mm, 66 * mm, 34 * mm, 38 * mm]))
    s.append(Spacer(1, 4))
    s.append(para("第十五条　供应商年审：所有供应商须在每年 <b>3 月 31 日前</b>完成年度复审，"
                  "逾期未复审的系统自动冻结其交易资格。", BODY))
    s.append(para("第十六条　<font color='#b30000'><b>连续两次年度复审被评为 C 级的供应商，"
                  "移出合格供应商名录</b></font>，两年内不得重新申请准入。", BODY))
    s.append(para("第十七条　新增供应商准入需提交：营业执照、统一社会信用代码证、"
                  "近两年审计报告、主要业绩证明，由采购部初审、风险管理部复核。", BODY))

    # ===== 六、期货合约条款要点 =====
    s.append(para("六、期货合约条款与交割规则要点", H1))
    s.append(para("第十八条　交易时间：日盘上午 9:00 至 11:30，下午 13:30 至 15:00；"
                  "部分品种设夜盘，具体以交易所公告为准。", BODY))
    s.append(para("第十九条　合约月份：主力合约通常为 1、5、9 月合约，"
                  "套保建仓应优先选择与现货敞口期限匹配的合约月份。", BODY))
    s.append(para("第二十条　最后交易日：合约到期月份的 <b>第 15 个交易日</b>。"
                  "临近最后交易日应提前移仓，避免进入交割环节。", BODY))
    s.append(para("第二十一条　涨跌停板幅度：常规品种为上一交易日结算价的 ±4%，"
                  "特殊时期交易所可调整幅度，调整后同时提高保证金标准。", BODY))
    s.append(para("第二十二条　交割方式：采用实物交割。不具备交割资质的，"
                  "必须在最后交易日前完成平仓或移仓。", BODY))

    # ===== 七、台账与报告 =====
    s.append(para("七、台账管理与报告制度", H1))
    s.append(para("第二十三条　业务部门应逐笔登记套期保值台账，内容包括："
                  "开仓日期、合约代码、方向、数量、成交价、保证金、对应现货敞口、当前盈亏。", BODY))
    s.append(para("第二十四条　报告要求：风险管理部每日报送持仓与风险度日报；"
                  "每月 5 日前报送上月套保效果评估报告；"
                  "季度末报送套保有效性分析（以 80% 为有效性达标线）。", BODY))
    s.append(para("第二十五条　套保有效性评估采用比率分析法："
                  "套期工具公允价值变动 / 被套保项目公允价值变动，"
                  "比率在 <b>80% 至 125%</b> 区间内认定为高度有效。", BODY))

    # ===== 八、术语表 =====
    s.append(para("八、术语表", H1))
    s.append(table([
        ["术语", "释义"],
        ["基差", "现货价格与期货价格之差，即 基差 = 现货价 − 期货价"],
        ["套保比例", "被套保现货数量占风险敞口总量的百分比"],
        ["风险度", "持仓保证金占用与客户权益的比值，越高风险越大"],
        ["移仓", "将临近到期的持仓平掉，同时在远月合约建立同方向持仓"],
        ["升贴水", "不同交割月份合约之间的价差，或不同交割品级的价格差异"],
        ["套保有效性", "套期工具与被套保项目公允价值变动的匹配程度"],
    ], [28 * mm, 114 * mm]))

    s.append(Spacer(1, 10 * mm))
    s.append(para("—— 本手册由风险管理部负责解释与修订，自发布之日起施行 ——", NOTE))
    return s


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "docs", "kb", "SmartHedge套期保值与风险管理制度手册.pdf")
    os.makedirs(os.path.dirname(out), exist_ok=True)

    doc = SimpleDocTemplate(
        out, pagesize=A4,
        leftMargin=25 * mm, rightMargin=25 * mm,
        topMargin=22 * mm, bottomMargin=20 * mm,
        title="SmartHedge 套期保值与风险管理制度手册",
        author="SmartHedge Risk Hub",
    )
    doc.build(build_story())
    size = os.path.getsize(out)
    print("生成成功: %s (%.1f KB)" % (out, size / 1024.0))


if __name__ == "__main__":
    main()
