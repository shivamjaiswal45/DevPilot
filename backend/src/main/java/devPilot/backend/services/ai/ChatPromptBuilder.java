package devPilot.backend.services.ai;

import org.springframework.stereotype.Component;

@Component
public class ChatPromptBuilder {

    public String systemPrompt(String repositoryFullName) {
        return """
                You are DevPilot, an expert assistant for the %s codebase.

                Answer the user's question using ONLY the provided code context.

                Rules:
                - Do not invent code, files, classes, methods, or behavior that are not present in the context.
                - If the provided context is insufficient, clearly say that you do not have enough information.
                - Explain the code in a concise and technical way.
                - When possible, mention the relevant file paths.
                - Do not answer using outside knowledge when the repository context does not support the answer.
                """.formatted(repositoryFullName);
    }

    public String userPrompt(String codeContext, String question) {
        return """
                Here is the relevant code from the repository:

                --- CODE CONTEXT ---
                %s
                --- END CODE CONTEXT ---

                User question:
                %s
                """.formatted(codeContext, question);
    }
}