package cn.spider.framework.transaction.sdk.datasource.isolate;

public enum TransactiomOperationStatus {
    COMMIT("提交"),
    ROLL_BACK("回滚")
    ;
    TransactiomOperationStatus(String desc) {
        this.desc = desc;
    }

    private String desc;
}
