-- =============================================================================
-- 001_search_ner_dict_entry.sql
--
-- NER 可编辑词典源。取代当前随程序发布的静态词典
-- （ai-search-server/src/main/resources/ner_dict.txt 与 ner/ner_dict_overrides.tsv）。
--
-- 目标：MySQL 8.0.16+（使用了 CHECK 约束与 utf8mb4_0900 排序规则）
-- 幂等：可重复执行，不会覆盖已有数据
--
-- 与 Java 侧的耦合点（改本表前先读这三处）：
--   - DictionaryNerRecognizer.normalizeKeyword  → term_norm 的归一化规则
--   - DictionaryNerRecognizer.priorities        → 标签优先级，参与冲突裁决
--   - NerFieldMapping.fieldMapping              → entity_type 的合法取值来源
--
-- 若后续引入 Flyway，将本文件重命名为 V001__search_ner_dict_entry.sql 即可。
-- =============================================================================

CREATE TABLE IF NOT EXISTS search_ner_dict_entry (

    id              BIGINT       NOT NULL AUTO_INCREMENT
                    COMMENT '主键',

    tenant_id       BIGINT       NOT NULL DEFAULT 0
                    COMMENT '词典作用域。单租户期固定为 0；所有查询与唯一键都必须带上本列，避免将来接多租户时改不动',

    term            VARCHAR(256) NOT NULL
                    COMMENT '人工可读原词。保留原始大小写与全半角，仅用于后台展示和审核，不参与匹配。匹配一律走 term_norm',

    term_norm       VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
                    COMMENT '归一化匹配键，由应用写入而非人工填写。规则必须与 DictionaryNerRecognizer.normalizeKeyword 逐字一致（当前实现仅做 toLowerCase(Locale.ROOT)，不做 NFKC、不做全半角折叠、不去空格，所以「苹果 15」与「苹果15」是两条独立记录）。本列单独指定 utf8mb4_bin，使唯一键的比较语义与 Java 侧 HashMap 键一一对应；若沿用表默认的 ai_ci，MySQL 会把大小写与变音符号不同的词判为重复而拒绝入库',

    entity_type     VARCHAR(32)  NOT NULL
                    COMMENT '实体类型。取值必须已存在于 NerFieldMapping.fieldMapping —— 没有 ES 字段映射的标签，识别出来也无处可用。新增标签是一次代码变更而不是数据变更：需同步 NerFieldMapping、DictionaryNerRecognizer.priorities，并 ALTER 本表的 ck_ner_dict_entity_type 约束',

    canonical_value VARCHAR(512)     NULL
                    COMMENT '规范值。别名统一后的标准写法，如 HUAWEI、华为 均归一到 华为。为空表示与 term 相同',

    biz_ref_id      VARCHAR(128)     NULL
                    COMMENT '业务引用。指向品牌/品类/型号主数据的业务主键。source_type=MASTER_DATA 时应当非空，用于回溯来源与后续增量同步',

    priority        SMALLINT     NOT NULL DEFAULT 0
                    COMMENT '显式优先级，对应 DictionaryNerRecognizer 的 explicitPriority，值越大越优先，0 表示不干预。完整裁决顺序为：显式优先级 DESC → 标签优先级 DESC → 词长 DESC → 起始位置 ASC，与 CANDIDATE_COMPARATOR 一致。仅在需要让完整系列/品类压过其中较短的词时才设置，不要当作通用权重使用',

    match_mode      VARCHAR(16)  NOT NULL DEFAULT 'AUTO'
                    COMMENT '匹配模式。Java 侧目前没有这个开关，边界规则硬编码在 DictionaryNerRecognizer.hasValidBoundary：纯 ASCII 词做左右词边界校验，中文词走纯 AC 匹配。因此第一版只允许 AUTO，即沿用该硬编码行为；EXACT/BOUNDARY 待 Java 实现后再放开，否则会出现后台配了却不生效',

    usage_scope     VARCHAR(16)  NOT NULL DEFAULT 'BOTH'
                    COMMENT '用途范围。RUNTIME=仅线上匹配，TRAINING=仅训练与预标注，BOTH=两者。线上坏例修复类词条（如为了配件排除而把「手机壳」整体提权）应设为 RUNTIME —— 那是检索侧的临时规则，当成标注真值喂给模型会把 hack 固化进模型',

    source_type     VARCHAR(16)  NOT NULL
                    COMMENT '来源。MASTER_DATA=业务主数据同步，MANUAL=人工录入，NER=模型产出，LLM=大模型产出。导出给训练使用时必须排除 NER 与 LLM：模型产物回流成训练集会造成自举污染，错误被逐版固化放大',

    source_ref_id   VARCHAR(128)     NULL
                    COMMENT '来源引用。审核任务号或上游数据批次号，用于追溯这条词是怎么进来的',

    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                    COMMENT '状态。DRAFT=草稿，APPROVED=已审核可发布，DISABLED=停用。只有 APPROVED 会被纳入发布版本。生效期由发布版本（search_ner_dict_version）单独控制，本表不设 effective_from/effective_to，避免两套生效机制互相打架',

    row_version     INT          NOT NULL DEFAULT 0
                    COMMENT '乐观锁版本号，每次更新加一。绝大多数行由主数据同步批量写入，本列主要保护后台并发人工编辑不被互相覆盖',

    created_by      VARCHAR(64)      NULL
                    COMMENT '创建人账号。机器同步写入固定标识（如 SYSTEM），便于区分人工决策与自动生成',

    updated_by      VARCHAR(64)      NULL
                    COMMENT '最后修改人账号',

    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    COMMENT '创建时间',

    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
                    COMMENT '最后修改时间。增量导出以本列为水位线',

    PRIMARY KEY (id),

    -- 一词一标签：唯一键刻意不含 entity_type。
    -- DictionaryNerRecognizer 运行时是 Map<term_norm, entry>，同一个归一化词只会保留一条，
    -- 由 ENTRY_COMPARATOR 静默择优。若这里允许一词多标签，冲突就发生在运行时而不是录入时，
    -- 后台会看到两条都是 APPROVED 却只有一条真正生效。
    UNIQUE KEY uk_ner_dict_term (tenant_id, term_norm),

    KEY idx_ner_dict_export  (tenant_id, status, usage_scope) COMMENT '全量导出发布快照',
    KEY idx_ner_dict_admin   (tenant_id, entity_type, status) COMMENT '后台按实体类型筛选',
    KEY idx_ner_dict_updated (tenant_id, updated_at)          COMMENT '按水位线增量导出',

    CONSTRAINT ck_ner_dict_entity_type CHECK (entity_type IN (
        'BRAND', 'CATEGORY', 'PRODUCT', 'SERIES', 'MODEL', 'COLOR', 'MATERIAL',
        'SPEC', 'FUNCTION', 'STYLE', 'AUDIENCE', 'SCENE', 'REGION',
        'ORGANIZATION', 'PERSON', 'ATTRIBUTE', 'MODIFIER')),
    CONSTRAINT ck_ner_dict_match_mode  CHECK (match_mode  IN ('AUTO')),
    CONSTRAINT ck_ner_dict_usage_scope CHECK (usage_scope IN ('RUNTIME', 'TRAINING', 'BOTH')),
    CONSTRAINT ck_ner_dict_source_type CHECK (source_type IN ('MASTER_DATA', 'MANUAL', 'NER', 'LLM')),
    CONSTRAINT ck_ner_dict_status      CHECK (status      IN ('DRAFT', 'APPROVED', 'DISABLED'))

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  ROW_FORMAT = DYNAMIC
  COMMENT = 'NER 可编辑词典源。一词一标签，冲突在录入时暴露而非运行时静默择一；发布快照见 search_ner_dict_version';


-- -----------------------------------------------------------------------------
-- 种子数据：现有 ner/ner_dict_overrides.tsv 的 11 条人工覆盖词。
-- 兼作 schema 自检 —— 其中 SERIES 与 SPEC 两个标签在设计文档原本的
-- 五值枚举（BRAND/CATEGORY/ATTRIBUTE/MODIFIER/MODEL）下是录不进来的。
--
-- term_norm 按 Java 现行规则填写：仅 toLowerCase，空格原样保留。
-- 手机壳/瑜伽垫 是检索侧的配件排除修复，标 RUNTIME 不进训练。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO search_ner_dict_entry
    (tenant_id, term, term_norm, entity_type, priority, usage_scope, source_type, status, created_by, updated_by)
VALUES
    (0, '苹果15',    '苹果15',    'SERIES',   100, 'BOTH',    'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, '苹果 15',   '苹果 15',   'SERIES',   100, 'BOTH',    'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, 'iPhone15',  'iphone15',  'SERIES',   100, 'BOTH',    'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, 'iPhone 15', 'iphone 15', 'SERIES',   100, 'BOTH',    'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, '大疆御3',   '大疆御3',   'MODEL',    100, 'BOTH',    'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, '御3',       '御3',       'MODEL',     90, 'BOTH',    'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, 'Mavic3',    'mavic3',    'MODEL',     90, 'BOTH',    'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, 'Mavic 3',   'mavic 3',   'MODEL',     90, 'BOTH',    'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, '2匹',       '2匹',       'SPEC',      80, 'BOTH',    'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, '手机壳',    '手机壳',    'CATEGORY', 120, 'RUNTIME', 'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM'),
    (0, '瑜伽垫',    '瑜伽垫',    'CATEGORY', 120, 'RUNTIME', 'MANUAL', 'APPROVED', 'SYSTEM', 'SYSTEM');


-- =============================================================================
-- 导出契约（Java 侧与训练侧共用同一份产物，不要各写各的导出）
--
-- 产物格式与现有词典文件保持一致，三列 TSV：term \t entity_type \t priority
-- =============================================================================

-- 线上运行时词典
-- SELECT term, entity_type, priority
--   FROM search_ner_dict_entry
--  WHERE tenant_id = 0
--    AND status = 'APPROVED'
--    AND usage_scope IN ('RUNTIME', 'BOTH')
--  ORDER BY priority DESC, CHAR_LENGTH(term) DESC, id;

-- 训练与预标注词典：额外排除模型产出，避免自举污染
-- SELECT term, entity_type, priority
--   FROM search_ner_dict_entry
--  WHERE tenant_id = 0
--    AND status = 'APPROVED'
--    AND usage_scope IN ('TRAINING', 'BOTH')
--    AND source_type IN ('MASTER_DATA', 'MANUAL')
--  ORDER BY priority DESC, CHAR_LENGTH(term) DESC, id;

-- 每次导出都要落一个 version_id 与内容 hash（见 search_ner_dict_version），
-- 否则「这个模型是用哪一版词典做的弱监督预标注」无法回答，掉点了也定位不到是词典变了还是数据变了。
