# -*- coding: utf-8 -*-
import pytest

from tests.make_fixtures import build_all


@pytest.fixture(scope="session")
def fixtures():
    return build_all()
