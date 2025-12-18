export interface Env {
  MELODY_BUCKET: R2Bucket;
  MIDI_BUCKET: R2Bucket;
  MELODY_PUBLIC_BASE: string; // 예: https://pub-86701e7243ce47338339a06872cba4e5.r2.dev
  MIDI_PUBLIC_BASE: string;   // 예: https://pub-cac2af06737f4edeb63de2e621719226.r2.dev
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    // CORS preflight
    if (request.method === "OPTIONS") {
      return handleOptions();
    }

    const q = url.searchParams.get("q");
    if (!q || q.trim().length === 0) {
      return json(
        { error: "Missing query parameter 'q'" },
        400
      );
    }

    // type=melody | midi (기본: melody)
    const type = (url.searchParams.get("type") || "melody").toLowerCase();

    let bucket: R2Bucket;
    let publicBase: string;
    let bucketName: string;

    if (type === "midi") {
      bucket = env.MIDI_BUCKET;
      publicBase = env.MIDI_PUBLIC_BASE;
      bucketName = "midi";
    } else {
      bucket = env.MELODY_BUCKET;
      publicBase = env.MELODY_PUBLIC_BASE;
      bucketName = "melody";
    }

    try {
      const matches = await searchInBucket(bucket, publicBase, q, bucketName);

      return json(
        {
          bucket: bucketName,
          query: q,
          matches,
        },
        200
      );
    } catch (e: any) {
      console.error("Worker search error", e);
      return json(
        {
          error: "Internal error while searching bucket",
          details: e?.message ?? String(e),
        },
        500
      );
    }
  },
};

/**
 * R2 버킷에서 전체 리스트를 가져와서
 * key에 q 문자열이 포함된 파일만 필터링
 */
async function searchInBucket(
  bucket: R2Bucket,
  publicBase: string,
  query: string,
  bucketName: string
): Promise<Array<{ key: string; size: number | null; url: string }>> {
  const normalizedQuery = query.toLowerCase();

  const results: Array<{ key: string; size: number | null; url: string }> = [];

  let cursor: string | undefined = undefined;
  let pageCount = 0;
  const MAX_PAGES = 5; // 안전을 위해 최대 몇 페이지까지만 조회 (필요시 늘리면 됨)

  do {
    const listResp = await bucket.list({
      // prefix를 쓰고 싶으면 여기서 설정 가능
      limit: 1000,
      cursor,
    });

    for (const obj of listResp.objects) {
      const keyLower = obj.key.toLowerCase();
      if (keyLower.includes(normalizedQuery)) {
        const url = buildPublicUrl(publicBase, obj.key);
        results.push({
          key: obj.key,
          size: obj.size ?? null,
          url,
        });
      }
    }

    cursor = listResp.truncated ? listResp.cursor : undefined;
    pageCount++;
  } while (cursor && pageCount < MAX_PAGES);

  return results;
}

/**
 * 퍼블릭 도메인 + key로 최종 URL 생성
 * - base 뒤의 / 처리
 * - key는 path segment 단위로 encode
 */
function buildPublicUrl(base: string, key: string): string {
  const trimmedBase = base.replace(/\/+$/, ""); // 끝의 / 제거
  const encodedKey = key
    .split("/")
    .map((segment) => encodeURIComponent(segment))
    .join("/");
  return `${trimmedBase}/${encodedKey}`;
}

/**
 * JSON response + CORS 헤더
 */
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET,OPTIONS",
      "Access-Control-Allow-Headers": "*",
    },
  });
}

/**
 * CORS preflight
 */
function handleOptions(): Response {
  return new Response(null, {
    status: 204,
    headers: {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET,OPTIONS",
      "Access-Control-Allow-Headers": "*",
      "Access-Control-Max-Age": "86400",
    },
  });
}
