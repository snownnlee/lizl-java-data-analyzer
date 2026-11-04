package com.example.fixture;

/**
 * 测试夹具：必须放在库包外——{@code CommonUtil.isNotProjectInnerClass} 会把
 * {@code cn.lizl.project.tool.analysis} 下的类判为「本库内部类」，不生成 toString 字段。
 */
public final class ValidatorDtos {

    private ValidatorDtos() {
    }

    /** 模拟 Lombok {@code @Data}：toString 把凭证拼进字符串 */
    public static class LeakyDto {

        private String userName = "张三";

        private String password = "leak-pass-789";

        @Override
        public String toString() {
            return "LeakyDto(userName=" + userName + ", password=" + password + ")";
        }
    }

    public static class PlainDto {

        private String name = "plain-holder-value";

        @Override
        public String toString() {
            return "PlainDto(name=" + name + ")";
        }
    }
}
