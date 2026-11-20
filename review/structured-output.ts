const format = {
  type: "json_schema",
  name: "code_review",
  strict: true,
  schema: {
    type: "object",
    properties: {
      problem: { type: "string" },
      suggestion: { type: "string" },
      urgency: { type: "string", enum: ["P0", "P1", "P2", "无"] },
    },
    required: ["problem", "suggestion", "urgency"],
    additionalProperties: false,
  },
};

export default function (pi) {
  pi.on("before_provider_request", ({ payload }, ctx) => {
    const api = ctx.model?.api;
    if (api === "openai-responses") {
      return { ...payload, text: { ...payload.text, format } };
    }
    if (api === "openai-completions") {
      const { type, ...json_schema } = format;
      return { ...payload, response_format: { type, json_schema } };
    }
    process.stderr.write(`审查未配置 ${api} 接口的 JSON Schema 输出\n`);
    ctx.abort();
  });
}
