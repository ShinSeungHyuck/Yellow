export interface Env {
  MELODY: R2Bucket;
  MIDI: R2Bucket;

  // (권장) 버킷을 Public + 커스텀 도메인 붙였다면 base URL을 여기에
  // 예) https://melody.example.com , https://midi.example.com
  MELODY_PUBLIC_BASE: string;
  MIDI_PUBLIC_BASE: string;
}

function baseTitleFromKey(key: string): string {
  const last = key.split("/").pop() ?? key;
  return last.replace(/\.[^.]+$/, ""); // 확장자 제거
}

// key에 공백/특수문자 있을 수 있어서 segment 단위 인코딩
function encodeKeyPreserveSlash(key: string): string {
  return key.split("/").map(encodeURIComponent).join("/");
}

function urlJoin(base: string, key: string): string {
  const b = base.endsWith("/") ? base : base + "/";
  return b + encodeKeyPreserveSlash(key);
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "access-control-allow-origin": "*",
      "cache-control": "public, max-age=30",
    },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // CORS preflight
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "GET,OPTIONS",
          "access-control-allow-headers": "content-type",
        },
      });
    }

    if (url.pathname === "/catalog") {
      const limit = Math.min(parseInt(url.searchParams.get("limit") ?? "200", 10) || 200, 1000);

      // melody는 페이지네이션 제공
      const cursor = url.searchParams.get("cursor") ?? undefined;

      // midi는 “매칭용 맵”을 만들기 위해 (단순 예시로) 최대 1000개만 가져옴
      // 곡이 더 많으면: (1) KV/캐시로 맵 저장하거나 (2) 미리 catalog.json 생성 방식 권장
      const [melodyListed, midiListed] = await Promise.all([
        env.MELODY.list({ limit, cursor }),  // list()는 최대 1000개 반환 :contentReference[oaicite:3]{index=3}
        env.MIDI.list({ limit: 1000 }),
      ]);

      const midiMap = new Map<string, { key: string; url: string }>();
      for (const o of midiListed.objects) {
        const title = baseTitleFromKey(o.key);
        midiMap.set(title, { key: o.key, url: urlJoin(env.MIDI_PUBLIC_BASE, o.key) });
      }

      const items: Array<{ title: string; melodyUrl: string; midiUrl: string; key: string }> = [];
      for (const o of melodyListed.objects) {
        const title = baseTitleFromKey(o.key);
        const midi = midiMap.get(title);
        if (!midi) continue;

        items.push({
          title,
          key: o.key,
          melodyUrl: urlJoin(env.MELODY_PUBLIC_BASE, o.key),
          midiUrl: midi.url,
        });
      }

      return json({
        items,
        truncated: melodyListed.truncated,
        cursor: melodyListed.cursor ?? null,
      });
    }

    return new Response("Not found", { status: 404 });
  },
};
