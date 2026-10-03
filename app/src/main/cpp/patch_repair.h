#pragma once
#include <cstdint>
#include <vector>
namespace ruyo {
// Copies complete source patches into missing lettering. Pixels outside mask are immutable.
std::vector<int32_t> repair(const std::vector<int32_t>& source, int width, int height,
                           const std::vector<uint8_t>& mask, const std::vector<uint8_t>& excluded);
}
