Question: {query}

Answer using ONLY the numbered sources below. Do not use prior knowledge.
You may use the earlier conversation to understand what the question refers to.
---------------------
{context}
---------------------
Rules:
- Citations are REQUIRED: end every sentence that states a fact with the number of the source it comes from,
  e.g. "`launch` returns a `Job` [1]." Use [1][2] when a sentence uses two sources. Only use numbers listed above.
- Do not add a sources list at the end.
- If the sources do not contain the answer, reply exactly: "I don't know based on the loaded docs."
- Keep it under 120 words. Include a short Kotlin snippet when it helps (no citation inside code).
- Do not mention "the context" or "the sources" in your answer.
