// src/worker.ts

export interface Env {
  MELODY_BUCKET: R2Bucket;
  MIDI_BUCKET: R2Bucket;

  // public base URLs (must end with "/")
  MELODY_PUBLIC_BASE: string;
  MIDI_PUBLIC_BASE: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/search") {
      return handleSearch(request, env);
    }

    return new Response(
      JSON.stringify({
        error: "Not found",
        message: "Use /search?bucket=melody|midi&q=keyword",
      }),
      {
        status: 404,
        headers: corsHeaders({ "Content-Type": "application/json" }),
      }
    );
  },
} satisfies ExportedHandler<Env>;

function corsHeaders(extra: Record<string, string> = {}): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    ...extra,
  };
}

async function handleSearch(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const bucketName = url.searchParams.get("bucket") || "melody";
  const q = (url.searchParams.get("q") || "").trim();

  if (!q) {
    return new Response(
      JSON.stringify({ error: "Missing query parameter 'q'" }),
      {
        status: 400,
        headers: corsHeaders({ "Content-Type": "application/json" }),
      }
    );
  }

  let bucket: R2Bucket;
  let publicBase: string;

  if (bucketName === "melody") {
    bucket = env.MELODY_BUCKET;
    publicBase = env.MELODY_PUBLIC_BASE;
  } else if (bucketName === "midi") {
    bucket = env.MIDI_BUCKET;
    publicBase = env.MIDI_PUBLIC_BASE;
  } else {
    return new Response(
      JSON.stringify({
        error: "Invalid bucket",
        message: "bucket must be 'melody' or 'midi'",
      }),
      {
        status: 400,
        headers: corsHeaders({ "Content-Type": "application/json" }),
      }
    );
  }

  // ensure trailing slash
  if (!publicBase.endsWith("/")) {
    publicBase = publicBase + "/";
  }

  // 간단히 전체 리스트를 받아 substring 매칭 (최대 1000개)
  const objects = await bucket.list({ limit: 1000 });

  const queryLower = q.toLowerCase();
  const matches = objects.objects
    .filter((obj) => obj.key.toLowerCase().includes(queryLower))
    .map((obj) => {
      const encodedKey = encodeURIComponent(obj.key);
      return {
        key: obj.key,
        size: obj.size,
        url: publicBase + encodedKey,
      };
    });

  const body = JSON.stringify({
    bucket: bucketName,
    query: q,
    matches,
  });

  return new Response(body, {
    status: 200,
    headers: corsHeaders({ "Content-Type": "application/json" }),
  });
}
