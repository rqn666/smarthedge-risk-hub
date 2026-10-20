# -*- coding: utf-8 -*-
"""
生成「套期保值业务制度」语料 PDF —— SmartHedge_Risk_Hub AI 知识库（RAG）数据准备

用途：项目里没有现成的电子版制度文档，按套保业务体系整理 15 份制度文件作为知识库语料，
      生成 PDF 后通过 POST /api/ai/kb/upload 入库，用于验证「解析 → 切分 → 向量化 →
      检索 → 引用溯源」整条链路，并作为简历里「知识库数据量与来源」的可复跑依据。

用法：
    python gen-kb-docs.py [输出目录]        # 默认输出到 ../kb-corpus

依赖：reportlab（中文字体用 Windows 自带的 simhei.ttf）
"""
import os
import random
import sys

from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

FONT_NAME = "SimHei"
FONT_PATH = r"C:\Windows\Fonts\simhei.ttf"

random.seed(20260925)

# ============================ 参数池 ============================
VARIETIES = ["螺纹钢", "铁矿石", "焦炭", "焦煤", "铜", "铝", "锌", "黄金", "白银",
             "原油", "燃料油", "PTA", "甲醇", "尿素", "玻璃", "纯碱", "豆粕",
             "棕榈油", "白糖", "棉花", "工业硅", "碳酸锂", "瓶片"]
EXCHANGES = ["上海期货交易所", "大连商品交易所", "郑州商品交易所",
             "上海国际能源交易中心", "广州期货交易所", "中国金融期货交易所"]
DEPTS = ["风险管理部", "交易部", "财务部", "合规部", "采购部", "仓储物流部",
         "审计部", "法务部", "总经理办公会"]
KEEP_YEARS = [5, 10, 15]

# ============================ 文档清单 ============================
# group: A=衍生品/套保类  B=现货与供应商类  C=合规内控类
DOCS = [
    dict(t="套期保值业务管理办法", c="ZD-TB-001", d="风险管理部", g="A", v=8),
    dict(t="期货交易风险控制制度", c="ZD-TB-002", d="风险管理部", g="A", v=6),
    dict(t="期货合约与条款管理规范", c="ZD-TB-003", d="交易部", g="A", v=7),
    dict(t="期权业务管理办法", c="ZD-TB-004", d="风险管理部", g="A", v=5),
    dict(t="大宗商品价格风险管理办法", c="ZD-TB-005", d="风险管理部", g="A", v=8),
    dict(t="保证金与套保资金管理制度", c="ZD-TB-006", d="财务部", g="A", v=5),
    dict(t="交割与仓单管理办法", c="ZD-TB-007", d="仓储物流部", g="A", v=5),
    dict(t="套期保值会计核算办法", c="ZD-TB-008", d="财务部", g="A", v=4),
    dict(t="现货采购与销售管理制度", c="ZD-XH-001", d="采购部", g="B", v=9),
    dict(t="供应商准入与评级管理办法", c="ZD-XH-002", d="采购部", g="B", v=8),
    dict(t="供应商分类管理细则", c="ZD-XH-003", d="采购部", g="B", v=6),
    dict(t="内幕信息与合规管理制度", c="ZD-HG-001", d="合规部", g="C", v=4),
    dict(t="异常交易与应急处置预案", c="ZD-HG-002", d="风险管理部", g="C", v=5),
    dict(t="业务文档与档案管理制度", c="ZD-HG-003", d="合规部", g="C", v=3),
    dict(t="系统操作与权限管理办法", c="ZD-HG-004", d="合规部", g="C", v=3),
]

CHAPTERS = {
    "A": ["总则", "组织架构与职责分工", "业务操作流程", "风险控制与限额管理", "监督检查与考核", "附则"],
    "B": ["总则", "职责分工", "准入与评级管理", "日常管理与评价", "考核、退出与附则"],
    "C": ["总则", "管理职责", "管理要求", "检查、问责与附则"],
}
# 每章条文条数（与 CHAPTERS 一一对应）
COUNTS = {
    "A": [8, 10, 10, 10, 10, 8],
    "B": [8, 9, 10, 10, 9],
    "C": [8, 10, 11, 11],
}

# ============================ 句库 ============================
POOL_A = [
    "公司开展{subject}应当以规避现货价格风险为目的，禁止以投机或套利为目的进行交易。",
    "{dept}负责{subject}的归口管理，包括制度建设、额度审批、风险监控与效果评估。",
    "业务部门开展{subject}前应当提交书面申请，载明交易目的、标的品种、合约月份、计划数量与预计持有期限。",
    "单笔{subject}对应的现货数量原则上不得超过该品种年度采购或销售总量的{ratio}%。",
    "年度套保总量经{board}审批后执行，单笔新增额度超过{amount}万元的，须逐笔报{dept}审批。",
    "{dept}应当在收到申请后{days}个工作日内完成审核，审核通过的报分管领导签批后执行。",
    "参与{subject}的人员应当具备期货从业资格，并签署风险揭示书与合规承诺书。",
    "建仓前应当测算所需保证金规模，确保账户可用资金能够覆盖{ratio}%以上的追加保证金需求。",
    "涉及{variety}品种的持仓，应当密切关注{exchange}调整保证金比例、涨跌停板幅度与手续费标准的公告。",
    "持仓合约应当在最后交易日前的{days}个交易日完成平仓或移仓，原则上不得进入交割流程。",
    "各品种持仓数量应当与实际现货敞口相匹配，{dept}每周核对一次敞口与持仓的对应关系。",
    "当期货账户权益较建仓时下降超过{ratio}%时，交易人员应当立即报告{dept}并书面说明原因。",
    "出现涨跌停板单边市时，{dept}应当在收盘后{days}小时内组织复盘评估并形成书面报告。",
    "套期保值效果评价以现货与衍生品的综合损益为口径，会计期末由{dept2}出具评价意见。",
    "严禁未经授权擅自开仓、平仓或变更交易策略；违反规定的，按公司问责制度追究责任。",
    "交易指令应当经{dept}复核后方可下达，指令留痕保存期限不少于{keep}年。",
    "公司与期货公司、银行等合作机构签订的协议，由{dept2}审核后报{board}批准。",
    "{subject}涉及的保证金、结算准备金等资金往来，纳入公司统一资金计划管理。",
    "每月末由{dept}编制业务报告，内容包括持仓明细、浮动盈亏、保证金占用与主要风险指标。",
    "审计部每年至少开展一次{subject}专项审计，重点检查授权执行、限额遵守与账务处理情况。",
    "本制度与国家法律法规、{exchange}业务规则不一致的，以法律法规与交易所规则为准。",
    "各下属单位应当在本制度框架内制定实施细则，报{dept}备案后执行。",
    "信息技术部门应当保障交易系统与行情系统的稳定运行，并做好交易数据的每日备份。",
    "异常交易行为（频繁报撤单、自成交、大额报单）应当立即制止并记录，必要时向{exchange}报告。",
    "业务人员离岗、调岗时应当完成持仓、指令权限与操作密码的交接，并由{dept}确认。",
    "本制度由{dept}负责解释，自发布之日起施行，原有相关规定同时废止。",
    "套期保值会计处理应当符合企业会计准则关于套期会计的规定，由{dept2}确认套期有效性。",
    "持仓超过批准额度或超出规定品种范围的，{dept}有权要求限期平仓并追究相关人员责任。",
    "发生系统故障、行情异常或重大亏损等情形，{dept}负责人应当在{days}小时内报告分管领导。",
    "{dept}应当建立业务台账，完整记录每笔交易的申请、审批、执行与结算信息。",
]

POOL_B = [
    "供应商准入实行分级分类管理，按供应物资的重要程度划分为战略、核心、一般三类。",
    "申请准入的供应商应当提供营业执照、统一社会信用代码、经营范围与相关资质证明文件。",
    "{dept}负责供应商资料的初审，重点核查主体资格、经营资质与履约能力。",
    "供应商信用评价采用百分制，评价维度包括质量、价格、交付、服务与合规，权重分别为三十、二十五、二十五、十、十。",
    "评价得分{score}分以上列为战略供应商，{score2}至{score}分为核心供应商，低于{score2}分列入观察名单。",
    "新准入供应商实行考察期管理，{days}个月内不予增加采购份额。",
    "采购合同应当明确质量标准、交付期限、验收方式、结算周期与违约责任。",
    "涉及{variety}等大宗商品的采购，应当结合市场价格走势确定定价方式，可采用月度均价或点价模式。",
    "同一物资原则上应当保持不少于{num}家合格供应商，避免单一来源依赖。",
    "{dept}每年组织一次供应商现场评审，评审结果作为下一年度采购份额分配的依据。",
    "供应商提供虚假资料或发生重大质量事故的，立即暂停合作并移出合格供应商名录。",
    "供应商黑名单信息应当在公司范围内共享，禁止任何部门与黑名单供应商发生业务往来。",
    "采购人员与供应商存在亲属关系或其他利益关系的，应当主动申报并回避相关业务。",
    "采购价格应当与市场价格、同期同类采购价格进行比对，偏离超过{ratio}%的须书面说明原因。",
    "供应物资到货后由仓储部门与需求部门共同验收，验收不合格的按合同约定处理。",
    "供应商发票、对账单与入库单应当三单匹配，{dept2}审核无误后方可付款。",
    "对战略供应商可签订年度框架协议，明确价格调整机制与优先供应条款。",
    "供应商评价数据由{dept}归口管理，每季度更新并同步至采购与财务部门。",
    "各下属单位不得自行准入供应商，确因业务需要新增的，报{dept}审批后纳入统一名录。",
    "供应商年度评价结果低于{score2}分的应当制定改进计划，连续两年不达标的予以退出。",
    "涉及进口物资的供应商，还应当核查其报关资质与外汇结算能力。",
    "禁止将采购业务化整为零以规避审批权限，同类物资连续采购金额应当累计计算。",
    "供应商绩效数据、评审记录与处置决定应当归档保存，保存期限不少于{keep}年。",
    "公司建立供应商协同台账，实现订单、交付与结算信息的线上流转与留痕。",
    "本制度由{dept}负责解释，自发布之日起施行。",
    "供应商准入与评价工作应当接受审计部监督，评价过程资料留存备查。",
]

POOL_C = [
    "公司各部门应当严格执行本制度，违反规定的按问责制度追究责任。",
    "{dept}负责本制度的宣贯、检查与解释工作。",
    "各项业务活动应当做到事前审批、事中控制、事后检查，形成完整管理闭环。",
    "重要业务资料应当及时归档，归档内容包括申请、审批、合同、凭证与往来函件。",
    "档案实行归口管理，由{dept}统一编号、分类与保管，借阅须履行审批登记手续。",
    "电子档案应当与纸质档案同步归档，并做好异地备份，防止单点丢失。",
    "涉及商业秘密的文档应当标明密级，限制传阅范围，未经批准不得对外提供。",
    "岗位设置应当体现不相容职务分离原则，授权审批与业务执行不得由同一人担任。",
    "关键岗位实行定期轮岗，轮岗周期原则上不超过{keep}年。",
    "信息系统账号实行实名制，权限按最小必要原则授予，人员离岗时及时回收。",
    "操作日志与审批记录应当完整留存，不得删除或篡改，保存期限不少于{keep}年。",
    "内部检查中发现的问题应当形成清单，明确责任人与整改时限，由{dept}跟踪验证。",
    "问题整改完成后应当进行复盘，分析制度或流程缺陷，必要时修订本制度。",
    "发生重大风险事件，责任部门应当在{days}小时内报告{dept}，不得隐瞒、迟报或漏报。",
    "应急处置实行分级响应，一般事件由{dept}处置，重大事件报{board}决策。",
    "应急处置结束后应当在{days}个工作日内形成总结报告，提出改进措施。",
    "对隐瞒风险、伪造资料、越权操作等行为，视情节轻重给予通报、降级或解除劳动合同处理。",
    "涉嫌违法犯罪的，依法移交司法机关处理。",
    "本制度与上级单位制度不一致的，以上级制度为准。",
    "各部门可根据本制度制定操作细则，报{dept}备案后实施。",
    "本制度自发布之日起施行。",
]

POOL = {"A": POOL_A, "B": POOL_B, "C": POOL_C}

# 兜底句池：接在任何主句后面都通顺，用来把条文补足到制度文件的正常长度。
# 真实制度条文普遍在 80~150 字，只写一句四五十字的话读起来像提纲，不像文件。
SUPPLEMENT = [
    "上述事项应当完整留痕备查，相关记录保存期限不少于{keep}年。",
    "涉及{exchange}业务的，还应当同时遵守交易所业务规则的相关规定。",
    "未按规定执行的，由{dept}责令限期改正；造成损失的，按公司问责制度追究责任。",
    "各下属单位应当结合实际情况制定实施细则，报{dept}备案后执行。",
    "本条款的执行情况纳入相关部门年度考核范围。",
    "相关数据与台账应当每季度更新一次，并同步至{dept2}。",
    "对拒不配合检查或整改不到位的，{dept}有权暂停其相关业务权限。",
    "上述要求如与国家法律法规不一致的，以国家法律法规为准。",
    "经办人员应当在业务完成后{days}个工作日内完成资料整理与移交。",
    "涉及金额超过{amount}万元的，还应当报{board}审议后实施。",
    "相关岗位人员发生变动的，应当在{days}个工作日内完成工作交接。",
    "本条款所涉表单与台账模板由{dept}统一制定并下发。",
    "执行过程中发现制度缺陷的，应当及时提出修订建议并按程序报批。",
    "上述信息属于公司商业秘密的，应当按密级管理要求控制传阅范围。",
    "经批准的特殊情形应当形成书面说明，作为附件随业务资料一并归档。",
    "相关系统权限的授予与回收应当经{dept}审批，并留痕备查。",
    "对执行到位、成效明显的部门和个人，可按公司规定给予表彰或奖励。",
    "本条款由{dept}负责解释。",
]


def ctx_for(doc):
    """为一份文档生成填充上下文（保证同一文档内参数稳定，不同文档之间有差异）"""
    v = doc["v"]
    return dict(
        subject=doc["t"].replace("办法", "").replace("制度", "").replace("规范", "").replace("细则", "").replace("预案", ""),
        dept=doc["d"],
        dept2=random.choice([x for x in DEPTS if x != doc["d"]]),
        board=random.choice(["总经理办公会", "公司风险管理委员会", "董事会"]),
        variety=random.choice(VARIETIES[: v + 4]),
        exchange=random.choice(EXCHANGES),
        ratio=random.choice([5, 10, 15, 20, 30, 40, 50, 60, 70, 80]),
        days=random.choice([1, 2, 3, 5, 7, 10, 15, 24, 48]),
        amount=random.choice([200, 500, 800, 1000, 2000, 3000, 5000, 8000]),
        num=random.choice([2, 3, 4, 5]),
        score=random.choice([85, 88, 90]),
        score2=random.choice([60, 65, 70, 75]),
        keep=random.choice(KEEP_YEARS),
    )


def gen_articles(doc):
    """按章节结构生成条文列表：[(章标题, [条文, ...]), ...]"""
    pool = list(POOL[doc["g"]])
    random.shuffle(pool)
    # 同一份文档内：主体、品种、交易所、部门保持稳定，只让少量指标抖动，
    # 否则一份制度里一会儿螺纹钢一会儿尿素，读起来不像同一份文件
    base_ctx = ctx_for(doc)
    supp = list(SUPPLEMENT)
    random.shuffle(supp)
    out, idx, sidx = [], 0, 0
    for ch, cnt in zip(CHAPTERS[doc["g"]], COUNTS[doc["g"]]):
        arts = []
        for _ in range(cnt):
            ctx = dict(base_ctx)
            ctx.update(ratio=random.choice([5, 10, 15, 20, 30, 40]),
                       days=random.choice([1, 3, 5, 7, 10]),
                       amount=random.choice([200, 500, 1000, 2000, 5000]))
            # 主句：句库循环取用，每用完一轮重新洗牌，避免出现固定搭配
            if idx and idx % len(pool) == 0:
                random.shuffle(pool)
            main = pool[idx % len(pool)].format(**ctx)
            idx += 1
            # 兜底句：把条文补到制度文件的正常长度（80~150 字）
            if sidx and sidx % len(supp) == 0:
                random.shuffle(supp)
            arts.append(main + supp[sidx % len(supp)].format(**ctx))
            sidx += 1
        out.append((ch, arts))
    return out


def wrap(text, size, maxw):
    lines, cur = [], ""
    for ch in text:
        if pdfmetrics.stringWidth(cur + ch, FONT_NAME, size) <= maxw:
            cur += ch
        else:
            lines.append(cur)
            cur = ch
    if cur:
        lines.append(cur)
    return lines


def build_pdf(path, doc):
    title, code, dept = doc["t"], doc["c"], doc["d"]
    chapters = gen_articles(doc)

    W, H = A4
    left, right, top, bottom = 70, 70, 70, 70
    maxw = W - left - right
    body, lead = 10.5, 15.75

    c = canvas.Canvas(path, pagesize=A4)
    c.setTitle(title)
    c.setAuthor("SmartHedge_Risk_Hub")
    y = H - top

    def line(txt, size=body, indent=0, gap=1):
        nonlocal y
        for i, ln in enumerate(wrap(txt, size, maxw - indent)):
            y -= lead * gap if i == 0 else lead
            if y < bottom:
                c.showPage()
                y = H - top
                c.setFont(FONT_NAME, size)
            c.setFont(FONT_NAME, size)
            c.drawString(left + indent, y, ln)

    c.setFont(FONT_NAME, 17)
    c.drawCentredString(W / 2, y, title)
    y -= 28
    c.setFont(FONT_NAME, 10)
    c.drawCentredString(W / 2, y, "文件编号：%s        责任部门：%s" % (code, dept))
    y -= 34

    n, chars = 0, 0
    for ch, arts in chapters:
        y -= lead * 0.6
        c.setFont(FONT_NAME, 12.5)
        if y < bottom + 40:
            c.showPage()
            y = H - top
        c.drawString(left, y, ch)
        y -= lead
        for a in arts:
            n += 1
            chars += len(a)
            line("第%s条 %s" % (cn_num(n), a), gap=1.4)
            y -= lead * 0.25
    c.save()
    return chars


CN = "零一二三四五六七八九十"


def cn_num(n):
    """1 -> 一, 15 -> 十五, 24 -> 二十四, 42 -> 四十二"""
    if n <= 10:
        return CN[n] if n < 10 else "十"
    if n < 20:
        return "十" + CN[n - 10]
    if n % 10 == 0:
        return CN[n // 10] + "十"
    return CN[n // 10] + "十" + CN[n % 10]


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "kb-corpus")
    outdir = os.path.abspath(outdir)
    os.makedirs(outdir, exist_ok=True)
    pdfmetrics.registerFont(TTFont(FONT_NAME, FONT_PATH))

    total_chars, total_files = 0, 0
    print("%-34s %8s %8s" % ("文件名", "条文数", "正文字数"))
    print("-" * 56)
    for doc in DOCS:
        path = os.path.join(outdir, "%s_%s.pdf" % (doc["c"], doc["t"]))
        chars = build_pdf(path, doc)
        cnt = sum(COUNTS[doc["g"]])
        total_chars += chars
        total_files += 1
        print("%-34s %8d %8d" % (doc["t"], cnt, chars))
    print("-" * 56)
    print("合计：%d 份文档，正文约 %d 字，输出目录 %s" % (total_files, total_chars, outdir))


if __name__ == "__main__":
    main()
