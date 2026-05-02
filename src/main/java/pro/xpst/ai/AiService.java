package pro.xpst.ai;

public interface AiService {

    String generate(String aMessage);

    String getModel();

    void setModel(String aModel);
}
