"""geocoding Lambda ターゲットのテスト。

Nominatim への実アクセスはせず、``urllib.request.urlopen`` を差し替えて検証する。
外形（Gateway が渡す event / context の形）と、レート制御・camelCase 統一・
失敗時に例外を投げないことを確認する。
"""

from __future__ import annotations

import io
import json
import time
import unittest
from unittest import mock

import handler


def _response(payload: object) -> io.BytesIO:
    """urlopen のコンテキストマネージャ戻り値を模した object を作る。"""
    body = json.dumps(payload).encode("utf-8")

    class _Resp(io.BytesIO):
        def __enter__(self):  # noqa: D105
            return self

        def __exit__(self, *args):  # noqa: D105
            return False

    return _Resp(body)


NOMINATIM_ITEM = {
    "display_name": "恩賜箱根公園, 箱根町, 神奈川県, 日本",
    "name": "恩賜箱根公園",
    "lat": "35.2005",
    "lon": "139.0256",
}


class ResolveTest(unittest.TestCase):
    """ツール本体の入出力。"""

    def setUp(self) -> None:
        # テスト毎にレート制御をリセットし、待ち時間を消して高速化する。
        handler._rate_limiter = handler._RateLimiter(0.0)

    def test_camel_case_and_shape(self) -> None:
        """候補は camelCase（displayName）で返る。"""
        with mock.patch.object(handler.urllib.request, "urlopen", return_value=_response([NOMINATIM_ITEM])):
            result = handler.resolve_place_coordinates(["箱根 恩賜公園"])

        self.assertEqual(list(result.keys()), ["箱根 恩賜公園"])
        candidate = result["箱根 恩賜公園"][0]
        self.assertEqual(
            set(candidate.keys()),
            {"name", "displayName", "lat", "lng"},
            "4 項目だけ（増やすと LLM への入力トークンが増える）",
        )
        self.assertEqual(candidate["name"], "恩賜箱根公園")
        self.assertEqual(candidate["displayName"], NOMINATIM_ITEM["display_name"])
        self.assertAlmostEqual(candidate["lat"], 35.2005)
        self.assertAlmostEqual(candidate["lng"], 139.0256)

    def test_multiple_queries_resolved_in_one_call(self) -> None:
        """複数クエリを1回でまとめて解決する。"""
        with mock.patch.object(
            handler.urllib.request, "urlopen", side_effect=lambda *a, **k: _response([NOMINATIM_ITEM])
        ):
            result = handler.resolve_place_coordinates(["大涌谷", "芦ノ湖", "箱根神社"])

        self.assertEqual(len(result), 3)
        self.assertTrue(all(len(v) == 1 for v in result.values()))

    def test_name_falls_back_to_first_element_of_display_name(self) -> None:
        """name が空なら display_name の先頭要素を使う。"""
        item = dict(NOMINATIM_ITEM, name="")
        with mock.patch.object(handler.urllib.request, "urlopen", return_value=_response([item])):
            result = handler.resolve_place_coordinates(["箱根"])
        self.assertEqual(result["箱根"][0]["name"], "恩賜箱根公園")

    def test_blank_queries_are_skipped(self) -> None:
        """空文字・空白のみのクエリは呼ばずに落とす。"""
        with mock.patch.object(handler.urllib.request, "urlopen") as urlopen:
            result = handler.resolve_place_coordinates(["", "   "])
        self.assertEqual(result, {})
        urlopen.assert_not_called()

    def test_http_failure_returns_empty_list_not_exception(self) -> None:
        """個別の失敗はそのクエリを空にするだけ（他クエリの候補を捨てない）。"""
        def _side_effect(*_args, **_kwargs):
            raise handler.urllib.error.URLError("boom")

        with mock.patch.object(handler.urllib.request, "urlopen", side_effect=_side_effect):
            result = handler.resolve_place_coordinates(["箱根"])
        self.assertEqual(result, {"箱根": []})

    def test_item_without_coordinates_is_dropped(self) -> None:
        """座標が壊れている候補は落とす（LLM に不正な座標を渡さない）。"""
        broken = {"display_name": "どこか", "name": "どこか", "lat": "N/A", "lon": "N/A"}
        with mock.patch.object(
            handler.urllib.request, "urlopen", return_value=_response([broken, NOMINATIM_ITEM])
        ):
            result = handler.resolve_place_coordinates(["箱根"])
        self.assertEqual(len(result["箱根"]), 1)

    def test_non_list_response_is_tolerated(self) -> None:
        """Nominatim がリスト以外を返しても落ちない。"""
        with mock.patch.object(
            handler.urllib.request, "urlopen", return_value=_response({"error": "nope"})
        ):
            self.assertEqual(handler.resolve_place_coordinates(["箱根"]), {"箱根": []})


class RateLimiterTest(unittest.TestCase):
    """Nominatim Usage Policy の担保（送出間隔）。"""

    def test_interval_is_enforced_across_parallel_calls(self) -> None:
        """並列に投げても送出間隔が空く。"""
        handler._rate_limiter = handler._RateLimiter(0.05)
        started: list[float] = []

        def _side_effect(*_args, **_kwargs):
            started.append(time.monotonic())
            return _response([NOMINATIM_ITEM])

        with mock.patch.object(handler.urllib.request, "urlopen", side_effect=_side_effect):
            handler.resolve_place_coordinates(["a", "b", "c"])

        self.assertEqual(len(started), 3)
        gaps = [b - a for a, b in zip(sorted(started), sorted(started)[1:], strict=False)]
        for gap in gaps:
            # スケジューリング誤差を見て 0.05 に対し 0.04 を下限にする。
            self.assertGreaterEqual(gap, 0.04, f"送出間隔が詰まっている: {gaps}")

    def test_interval_persists_across_invocations(self) -> None:
        """Lambda コンテナ再利用時、呼び出しをまたいでも間隔が効く。"""
        limiter = handler._RateLimiter(0.05)
        limiter.acquire()  # 1回目は待たない
        start = time.monotonic()
        limiter.acquire()
        self.assertGreaterEqual(time.monotonic() - start, 0.04)


class HandlerTest(unittest.TestCase):
    """Gateway から渡る event / context の外形。"""

    def setUp(self) -> None:
        handler._rate_limiter = handler._RateLimiter(0.0)

    class _Ctx:
        def __init__(self, custom: dict | None) -> None:
            self.client_context = mock.Mock(custom=custom) if custom is not None else None

    def test_handler_resolves_with_expected_tool_name(self) -> None:
        """想定どおりのツール名なら解決する。"""
        ctx = self._Ctx({"bedrockAgentCoreToolName": "resolve_place_coordinates"})
        with mock.patch.object(handler.urllib.request, "urlopen", return_value=_response([NOMINATIM_ITEM])):
            result = handler.handler({"queries": ["箱根"]}, ctx)
        self.assertIn("箱根", result)

    def test_handler_accepts_prefixed_tool_name(self) -> None:
        """Gateway が <target>___<tool> 形式で渡しても受ける。"""
        ctx = self._Ctx({"bedrockAgentCoreToolName": "geocoding___resolve_place_coordinates"})
        with mock.patch.object(handler.urllib.request, "urlopen", return_value=_response([NOMINATIM_ITEM])):
            result = handler.handler({"queries": ["箱根"]}, ctx)
        self.assertIn("箱根", result)

    def test_handler_rejects_unknown_tool_name(self) -> None:
        """未対応のツール名は空ではなくエラーを返す（スキーマとのズレを黙って隠さない）。"""
        ctx = self._Ctx({"bedrockAgentCoreToolName": "something_else"})
        result = handler.handler({"queries": ["箱根"]}, ctx)
        self.assertIn("error", result)

    def test_handler_without_client_context_still_works(self) -> None:
        """ツール名が取れなくても呼び出しは落とさない。"""
        with mock.patch.object(handler.urllib.request, "urlopen", return_value=_response([NOMINATIM_ITEM])):
            result = handler.handler({"queries": ["箱根"]}, self._Ctx(None))
        self.assertIn("箱根", result)

    def test_handler_with_malformed_event(self) -> None:
        """queries が無い・リストでない場合は空を返す。"""
        self.assertEqual(handler.handler({}, self._Ctx(None)), {})
        self.assertEqual(handler.handler({"queries": "箱根"}, self._Ctx(None)), {})
        self.assertEqual(handler.handler(None, self._Ctx(None)), {})


if __name__ == "__main__":
    unittest.main()
