package dev.zencode.hook;

/** 条件比较操作符：精确、反向、正则、glob。 */
public enum Operator {
    EQ("=="),
    NE("!="),
    REGEX("=~"),
    GLOB("~=");

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
