import com.lpsm.vod.AdultContent;
public class AdultContentRegression {
    public static void main(String[] args) {
        String[] adult = {"Adultos", "XXX", "Erótico", "18 +", "+18", "Pornô", "Hentai"};
        String[] normal = {"Lançamentos", "Essex", "Sexo, Amor e Traição", "Toy Story"};
        for (String value : adult) if (!AdultContent.isAdultName(value)) throw new AssertionError(value);
        for (String value : normal) if (AdultContent.isAdultName(value)) throw new AssertionError(value);
        System.out.println("PASS: adult markers match backend classification; ordinary film titles preserved.");
    }
}
