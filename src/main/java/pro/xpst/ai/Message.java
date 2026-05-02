package pro.xpst.ai;

public record Message(Role role, String content) {
    public enum Role { USER, ASSISTANT }
}
