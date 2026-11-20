#!/usr/bin/env python3
"""Tests for the public Kotlin facade contract validator."""

import unittest

from validate_public_facade_contract import validate_sources, validate_text


class PublicFacadeContractTest(unittest.TestCase):
    def test_checked_in_public_facades_remain_non_inline(self) -> None:
        self.assertEqual([], validate_sources())

    def test_rejects_inline_and_crossinline_public_facade(self) -> None:
        path = "leader-consul/src/main/kotlin/io/bluetape4k/leader/consul/ConsulLeaderElector.kt"
        text = """\
inline fun <T> Endpoint.runIfLeader(
    lockName: String,
    crossinline action: () -> T,
): T? = action()
fun <T> Endpoint.runAsyncIfLeader(
    lockName: String,
    action: () -> T,
): T? = action()
"""
        self.assertEqual(
            [
                f"{path}#runIfLeader: public facade must not be inline",
                f"{path}#runIfLeader: public facade must not use crossinline",
            ],
            validate_text(path, text),
        )


if __name__ == "__main__":
    unittest.main()
