export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/catalog") {
      const limit = Math.min(parseInt(url.searchParams.get("limit") || "500", 10), 1000);

      // R2 list()는 최대 1000개씩
      const [mel, mid] = await Promise.all([
        env.MELODY.list({ limit }),
        env.MIDI.list({ limit }),
      ]);

      // key -> object
      const melodyMap = new Map();
      for (const o of mel.objects) {
        const base = stripExt(lastName(o.key));
        melodyMap.set(base, o.key);
      }

      const songs = [];
      for (const o of mid.objects) {
        const base = stripExt(lastName(o.key));
        const melodyKey = melodyMap.get(base);
        if (!melodyKey) continue;

        const title = base;
        songs.push({
          title,
          melodyUrl: joinPublicUrl(env.MELODY_PUBLIC_BASE, melodyKey),
          midiUrl: joinPublicUrl(env.MIDI_PUBLIC_BASE, o.key),
        });
      }

      songs.sort((a, b) => a.title.localeCompare(b.title));
      return Response.json({ songs });
    }

    return new Response("Not Found", { status: 404 });
  },
};

function lastName(key) {
  const p = key.split("/");
  return p[p.length - 1] || key;
}

function stripExt(name) {
  const i = name.lastIndexOf(".");
  return i > 0 ? name.slice(0, i) : name;
}

function joinPublicUrl(base, key) {
  // key에 공백/한글/특수문자 있을 수 있으니 경로 세그먼트별 인코딩
  const cleanBase = base.endsWith("/") ? base.slice(0, -1) : base;
  const encoded = key.split("/").map(encodeURIComponent).join("/");
  return `${cleanBase}/${encoded}`;
}
