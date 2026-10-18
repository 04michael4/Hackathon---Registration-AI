"""Fetches from Rate My Professors through its unofficial GraphQL endpoint (stdlib only): one school's roster, and the ratings of chosen professors.

Low volume on purpose: 100 items per request, a pause between requests, a hard request cap, and a stop on the first HTTP error or rate-limit
answer. Roster: aggregates only. Ratings: per rating only the id, posting date, clarity/helpfulness/difficulty scores, would-take-again, the
course label and the online flag; never the comment, tags, grade or anything else the student wrote. RMP's terms restrict automated access;
the project owner accepted that risk (ADR-0006).
"""
import json
import os
import ssl
import time
import urllib.error
import urllib.request

ENDPOINT = "https://www.ratemyprofessors.com/graphql"
# The public token the RMP website itself sends; it is not a secret and identifies no account.
PUBLIC_AUTH = "Basic dGVzdDp0ZXN0"
USER_AGENT = "course-registration-hackathon/0.1 (student project; low volume)"
QUERY = ("query($q:TeacherSearchQuery!,$after:String){newSearch{teachers(query:$q,first:100,after:$after){resultCount "
         "edges{node{id legacyId firstName lastName department avgRating avgDifficulty wouldTakeAgainPercent numRatings school{legacyId}}} "
         "pageInfo{hasNextPage endCursor}}}}")


CA_BUNDLES = ("/etc/ssl/cert.pem", "/opt/homebrew/etc/ca-certificates/cert.pem", "/etc/ssl/certs/ca-certificates.crt")


class FetchError(Exception):
    pass


def tls_context():
    """Certificate verification stays ON. Some Python builds ship without a CA bundle, so fall back to the operating system's."""
    context = ssl.create_default_context()
    if not context.cert_store_stats().get("x509_ca"):
        bundle = next((p for p in CA_BUNDLES if os.path.exists(p)), None)
        if bundle:
            context = ssl.create_default_context(cafile=bundle)
    return context


RATINGS_QUERY = ("query($id:ID!,$after:String){node(id:$id){... on Teacher{legacyId numRatings ratings(first:100,after:$after){"
                 "edges{node{legacyId date class clarityRating helpfulRating difficultyRating wouldTakeAgain isForOnlineClass}} "
                 "pageInfo{hasNextPage endCursor}}}}}")


def school_node_id(legacy_id):
    import base64
    return base64.b64encode(f"School-{legacy_id}".encode()).decode()


def post(variables, timeout=30, query=QUERY):
    body = json.dumps({"query": query, "variables": variables}).encode()
    request = urllib.request.Request(ENDPOINT, data=body, headers={"Content-Type": "application/json", "Authorization": PUBLIC_AUTH, "User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=timeout, context=tls_context()) as response:
            data = json.load(response)
    except urllib.error.HTTPError as exc:
        raise FetchError(f"HTTP {exc.code} from RMP; stopping (no retry)") from exc
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise FetchError(f"request failed: {exc}") from exc
    if data.get("errors"):
        raise FetchError("RMP returned errors: " + json.dumps(data["errors"])[:300])
    return data["data"]["newSearch"]["teachers"] if query is QUERY else data["data"]["node"]


def fetch_school_roster(school_legacy_id, max_requests=30, pause_seconds=2.0, sleep=time.sleep, poster=post):
    """Returns (teachers, result_count). Raises FetchError if the roster needs more than max_requests pages or a request fails."""
    teachers, after, requests, result_count = [], None, 0, None
    while True:
        if requests >= max_requests:
            raise FetchError(f"roster needs more than {max_requests} requests; raise --max-requests deliberately")
        if requests:
            sleep(pause_seconds)
        page = poster({"q": {"text": "", "schoolID": school_node_id(school_legacy_id)}, "after": after})
        requests += 1
        result_count = page["resultCount"]
        for edge in page["edges"]:
            node = edge["node"]
            if (node.get("school") or {}).get("legacyId") != school_legacy_id:
                raise FetchError("RMP returned a professor from another school; stopping")
            teachers.append(node)
        if not page["pageInfo"]["hasNextPage"]:
            break
        after = page["pageInfo"]["endCursor"]
    if len(teachers) != result_count:
        raise FetchError(f"fetched {len(teachers)} professors but RMP reported {result_count}")
    return teachers, result_count


def post_ratings(variables, timeout=30):
    return post(variables, timeout, query=RATINGS_QUERY)


def fetch_teacher_ratings(teachers, max_requests=80, pause_seconds=2.0, sleep=time.sleep, poster=post_ratings):
    """teachers: [(legacy_id, node_id)]. Returns {legacy_id: {"reported": numRatings, "ratings": [raw rating nodes]}}.

    The request cap covers ALL professors together, so a long list cannot turn into a large crawl."""
    result, requests = {}, 0
    for legacy_id, node_id in teachers:
        ratings, after, reported = [], None, None
        while True:
            if requests >= max_requests:
                raise FetchError(f"ratings need more than {max_requests} requests; raise --max-requests deliberately")
            if requests:
                sleep(pause_seconds)
            node = poster({"id": node_id, "after": after})
            requests += 1
            if not node or node.get("legacyId") != legacy_id:
                raise FetchError(f"RMP returned a different professor for {legacy_id}; stopping")
            reported = node["numRatings"]
            ratings.extend(edge["node"] for edge in node["ratings"]["edges"])
            if not node["ratings"]["pageInfo"]["hasNextPage"]:
                break
            after = node["ratings"]["pageInfo"]["endCursor"]
        result[legacy_id] = {"reported": reported, "ratings": ratings}
    return result
