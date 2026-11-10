package cn.debubu.tingbili.core.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val build = 1_000_000L

    @Test fun `same tag is never an update`() {
        assertFalse(
            UpdatePolicy.isUpdate(
                localVersion = "0.2.12",
                remoteTag = "v0.2.12",
                remotePublishedSec = 1_000_300L, // 构建→发布差 300s，超过容差
                localBuildSec = build,
            ),
        )
    }

    @Test fun `same tag with uppercase v and spaces is never an update`() {
        assertFalse(
            UpdatePolicy.isUpdate("0.2.12", " V0.2.12 ", 1_000_300L, build),
        )
    }

    @Test fun `different tag newer time is an update`() {
        assertTrue(
            UpdatePolicy.isUpdate("0.2.11", "v0.2.12", 1_000_300L, build),
        )
    }

    @Test fun `different tag but older release is not an update`() {
        assertFalse(
            UpdatePolicy.isUpdate("0.2.12", "v0.2.11", 900_000L, build),
        )
    }

    @Test fun `different tag within tolerance is not an update`() {
        assertFalse(
            UpdatePolicy.isUpdate("0.2.11", "v0.2.12", 1_000_030L, build),
        )
    }

    @Test fun `unparseable time is not an update`() {
        assertFalse(
            UpdatePolicy.isUpdate("0.2.11", "v0.2.12", null, build),
        )
    }

    // 时间条件本身的边界（原 UpdateChecker.isNewer 的用例，判定已并入 UpdatePolicy）

    @Test fun `beyond tolerance is newer`() {
        assertTrue(UpdatePolicy.isUpdate("0.2.11", "v0.2.12", 1_000_061L, build))
    }

    @Test fun `same build second is not newer`() {
        assertFalse(UpdatePolicy.isUpdate("0.2.11", "v0.2.12", 1_000_000L, build))
    }

    @Test fun `clock skew within tolerance is not newer`() {
        assertFalse(UpdatePolicy.isUpdate("0.2.11", "v0.2.12", 1_000_030L, build))
    }

    @Test fun `older release is not newer`() {
        assertFalse(UpdatePolicy.isUpdate("0.2.11", "v0.2.12", 999_000L, build))
    }
}
