package cn.vetech.aimall.service.ner;

import cn.vetech.aimall.model.dto.IntentResult;

/** 在线意图识别协议；蒸馏出的 ONNX NER 模型可按此接口替换规则实现。 */
public interface IntentRecognizer {
    IntentResult extract(String rawQuery);
}
