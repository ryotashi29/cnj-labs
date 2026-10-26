import base64
import json

import pytest

from outing_agent.conversation import LOCAL_ACTOR, actor_id


def _bearer(claims: dict) -> str:
    def enc(obj: dict) -> str:
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip("=")

    return f"Bearer {enc({'alg': 'RS256'})}.{enc(claims)}.sig"


def test_actor_id_は_JWT_の_sub_から取る():
    assert actor_id(_bearer({"sub": "user-sub-abc-1"})) == "user-sub-abc-1"


def test_Authorization_がなければ手元の実行として扱う():
    assert actor_id("") == LOCAL_ACTOR
    assert actor_id(None) == LOCAL_ACTOR


@pytest.mark.parametrize("auth", ["Basic abc", _bearer({"aud": "x"}), _bearer({"sub": "a:b"})])
def test_Authorization_があるのに読めなければ例外にする(auth):
    with pytest.raises(ValueError):
        actor_id(auth)
