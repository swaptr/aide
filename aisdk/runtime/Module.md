# Module runtime

`streamText`/`generateText` and the multi-round tool loop, structured output with repair,
embeddings, the image/speech/transcription/rerank/video wrappers, middleware, and `ToolLoopAgent`.
The loop's correctness core: a replayed assistant turn keeps its reasoning parts, their
`providerMetadata`, and their order.
