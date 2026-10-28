package cs_orgchart.faq;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The "FAQ & Request Guidelines" of a function (U5, function_faq.content): sections in order, each of one kind.
 * <ul>
 *   <li>GUIDELINES: items {text} (one guideline line each);</li>
 *   <li>MAILBOXES: items {label, email} (email null: "Email to be added");</li>
 *   <li>QUESTIONS: items {question, answer} (the answer may have several lines).</li>
 * </ul>
 * Plain text only; https:// and mailto: links in the texts are shown as links.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FaqDocument(List<Section> sections) {

    public static final String GUIDELINES = "GUIDELINES";
    public static final String MAILBOXES = "MAILBOXES";
    public static final String QUESTIONS = "QUESTIONS";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Section(String kind, String title, List<Item> items) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String text, String label, String email, String question, String answer) {
    }
}
