// Mock upstream: Anthropic + OpenAI, logs every request it receives.
import http from 'node:http';
const log = [];
const srv = http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    let j = {}; try { j = JSON.parse(body || '{}'); } catch {}
    log.push({ method: req.method, url: req.url, headers: req.headers, body: j });
    const u = req.url.split('?')[0];
    if (u === '/_log') { res.setHeader('content-type','application/json'); return res.end(JSON.stringify(log.splice(0))); }
    if (u.endsWith('/models')) {
      res.setHeader('content-type','application/json');
      return res.end(JSON.stringify(u.includes('anthropic') || req.headers['x-api-key']
        ? { data: [{ id: 'claude-sonnet-4-5', type: 'model', display_name: 'S' }, { id: 'claude-haiku-4-5', type: 'model', display_name: 'H' }], has_more: false }
        : { object: 'list', data: [{ id: 'gpt-5', object: 'model' }, { id: 'gpt-5-mini', object: 'model' }] }));
    }
    if (u.endsWith('/messages/count_tokens')) { res.setHeader('content-type','application/json'); return res.end('{"input_tokens":42}'); }
    if (u.endsWith('/messages')) {
      if (j.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        const ev = (t, d) => res.write(`event: ${t}\ndata: ${JSON.stringify(d)}\n\n`);
        ev('message_start', { type: 'message_start', message: { id: 'msg_1', type: 'message', role: 'assistant', model: j.model, content: [], stop_reason: null, usage: { input_tokens: 1000, output_tokens: 1 } } });
        ev('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } });
        ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'hello' } });
        ev('content_block_stop', { type: 'content_block_stop', index: 0 });
        ev('message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 500 } });
        ev('message_stop', { type: 'message_stop' });
        return res.end();
      }
      res.setHeader('content-type','application/json');
      return res.end(JSON.stringify({ id: 'msg_1', type: 'message', role: 'assistant', model: j.model, content: [{ type: 'text', text: 'hello' }], stop_reason: 'end_turn', usage: { input_tokens: 1000, output_tokens: 500 } }));
    }
    if (u.endsWith('/chat/completions')) {
      if (j.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write(`data: ${JSON.stringify({ id: 'c1', object: 'chat.completion.chunk', model: j.model, choices: [{ index: 0, delta: { role: 'assistant', content: 'hello' } }] })}\n\n`);
        res.write(`data: ${JSON.stringify({ id: 'c1', object: 'chat.completion.chunk', model: j.model, choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] })}\n\n`);
        res.write(`data: ${JSON.stringify({ id: 'c1', object: 'chat.completion.chunk', model: j.model, choices: [], usage: { prompt_tokens: 1000, completion_tokens: 500, total_tokens: 1500 } })}\n\n`);
        res.write('data: [DONE]\n\n'); return res.end();
      }
      res.setHeader('content-type','application/json');
      return res.end(JSON.stringify({ id: 'c1', object: 'chat.completion', model: j.model, choices: [{ index: 0, message: { role: 'assistant', content: 'hello' }, finish_reason: 'stop' }], usage: { prompt_tokens: 1000, completion_tokens: 500, total_tokens: 1500 } }));
    }
    if (u.endsWith('/responses')) {
      const r = { id: 'resp_1', object: 'response', status: 'completed', model: j.model, output: [{ type: 'message', id: 'm1', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text: 'hello', annotations: [] }] }], usage: { input_tokens: 1000, output_tokens: 500, total_tokens: 1500 } };
      if (j.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        const ev = (t, d) => res.write(`event: ${t}\ndata: ${JSON.stringify({ type: t, ...d })}\n\n`);
        ev('response.created', { sequence_number: 0, response: { ...r, status: 'in_progress', output: [], usage: null } });
        ev('response.output_text.delta', { sequence_number: 1, item_id: 'm1', output_index: 0, content_index: 0, delta: 'hello' });
        ev('response.completed', { sequence_number: 2, response: r });
        return res.end();
      }
      res.setHeader('content-type','application/json');
      return res.end(JSON.stringify({ id: 'resp_1', object: 'response', status: 'completed', model: j.model, output: [{ type: 'message', id: 'm1', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text: 'hello', annotations: [] }] }], usage: { input_tokens: 1000, output_tokens: 500, total_tokens: 1500 } }));
    }
    res.statusCode = 404; res.end('{"error":"mock: no route ' + u + '"}');
  });
});
srv.listen(Number(process.argv[2] || 9911), '127.0.0.1');
