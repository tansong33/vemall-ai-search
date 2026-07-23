package cn.vetech.aimall.model.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** NER 词典项：展示名称与数据库过滤 ID 分离，别名以逗号分隔。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DictionaryTerm {
    private String id;
    private String name;
    private String aliases;
}
