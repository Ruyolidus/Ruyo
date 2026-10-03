#include "patch_repair.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace ruyo {
namespace {
int channel(int32_t c, int s) { return (static_cast<uint32_t>(c) >> s) & 255; }
double distance(int32_t a, int32_t b) {
    double sum = 0;
    for (int s : {0, 8, 16}) { double d = channel(a,s)-channel(b,s); sum += d*d; }
    return sum;
}
}
std::vector<int32_t> repair(const std::vector<int32_t>& source, int w, int h,
                           const std::vector<uint8_t>& mask, const std::vector<uint8_t>& excluded) {
    if (w < 1 || h < 1 || int64_t(w)*h > 2000000 || source.size() != size_t(w)*h ||
        mask.size() != source.size() || excluded.size() != source.size())
        throw std::invalid_argument("Invalid repair dimensions");
    const int n = w*h, count = std::count(mask.begin(), mask.end(), uint8_t(1));
    if (count > 400000 || count >= n*.75) throw std::invalid_argument("Leave original artwork around the lettering");
    auto output = source;
    if (!count) return output;
    std::vector<uint8_t> known(n), queued(n);
    std::vector<int> donor(n,-1), queue;
    queue.reserve(count);
    // Integral exclusion map: donor patches must contain NO lettering (including other edits).
    std::vector<int> integral((w+1)*(h+1));
    for (int y=0;y<h;++y) {
        int row=0;
        for (int x=0;x<w;++x) {
            int i=y*w+x; known[i]=!mask[i];
            row += mask[i] || excluded[i] || channel(source[i],24)<250;
            integral[(y+1)*(w+1)+x+1]=integral[y*(w+1)+x+1]+row;
        }
    }
    auto valid = [&](int x,int y,int r) {
        if (x<r || y<r || x+r>=w || y+r>=h) return false;
        int a=x-r,b=y-r,c=x+r+1,d=y+r+1;
        return integral[d*(w+1)+c]-integral[b*(w+1)+c]-integral[d*(w+1)+a]+integral[b*(w+1)+a]==0;
    };
    int radius=4;
    std::vector<int> donors;
    for (;radius>=1;--radius) {
        for (int y=radius;y<h-radius;++y) for(int x=radius;x<w-radius;++x)
            if (valid(x,y,radius)) donors.push_back(y*w+x);
        if (!donors.empty()) break;
    }
    if (donors.empty()) throw std::invalid_argument("Not enough clean artwork around this mask");
    auto visitNeighbors = [&](int i, auto action) {
        int x=i%w,y=i/w;
        if(x) action(i-1); if(x+1<w) action(i+1); if(y) action(i-w); if(y+1<h) action(i+w);
    };
    for(int i=0;i<n;++i) if(mask[i]) {
        bool boundary=false; visitNeighbors(i,[&](int j){boundary |= known[j]!=0;});
        if(boundary){queue.push_back(i);queued[i]=1;}
    }
    uint32_t random=0x52a9b1U;
    auto next = [&](){ random ^= random<<13; random ^= random>>17; random ^= random<<5; return random; };
    for(size_t head=0;head<queue.size();++head) {
        const int target=queue[head], tx=target%w,ty=target/w;
        if(known[target]) continue;
        struct Sample { int dx,dy,index; double weight; };
        std::vector<Sample> samples;
        for(int dy=-radius;dy<=radius;++dy) for(int dx=-radius;dx<=radius;++dx) {
            int x=tx+dx,y=ty+dy;
            if(x>=0 && y>=0 && x<w && y<h && known[y*w+x])
                samples.push_back({dx,dy,y*w+x,mask[y*w+x] ? .35 : 1.0});
        }
        if(samples.empty()) throw std::runtime_error("Disconnected repair boundary");
        double best=std::numeric_limits<double>::infinity(); int chosen=-1;
        auto consider = [&](int candidate) {
            if(candidate<0 || candidate>=n) return;
            int cx=candidate%w,cy=candidate/w;
            if(!valid(cx,cy,radius)) return;
            // Very small displacement penalty breaks ambiguous matches in favor of nearby art.
            double score=.0001*((cx-tx)*(cx-tx)+(cy-ty)*(cy-ty));
            for(auto p:samples) {
                score+=p.weight*distance(output[p.index],source[(cy+p.dy)*w+cx+p.dx]);
                if(score>=best) return;
            }
            best=score; chosen=candidate;
        };
        // Continue source offsets already used next to this frontier.
        for(int dy=-radius;dy<=radius;++dy) for(int dx=-radius;dx<=radius;++dx) {
            int x=tx+dx,y=ty+dy;
            if(x<0||y<0||x>=w||y>=h) continue;
            int d=donor[y*w+x];
            if(d>=0) { int cx=d%w-dx,cy=d/w-dy; if(cx>=0&&cy>=0&&cx<w&&cy<h) consider(cy*w+cx); }
        }
        // Search actual local patches, then dispersed candidates, then refine the best match.
        for(int dy=-24;dy<=24;dy+=4) for(int dx=-24;dx<=24;dx+=4) {
            int x=tx+dx,y=ty+dy; if(x>=0&&y>=0&&x<w&&y<h) consider(y*w+x);
        }
        for(int k=0;k<96;++k) consider(donors[next()%donors.size()]);
        for(int step=8;step>=1;step/=2) {
            if(chosen<0) break;
            int cx=chosen%w,cy=chosen/w;
            for(int dy=-step;dy<=step;dy+=step) for(int dx=-step;dx<=step;dx+=step)
                if(cx+dx>=0&&cy+dy>=0&&cx+dx<w&&cy+dy<h) consider((cy+dy)*w+cx+dx);
        }
        if(chosen<0) throw std::runtime_error("No clean patch found");
        int cx=chosen%w,cy=chosen/w;
        // A small central patch advances the front without writing known pixels or averaging away texture.
        const int fill=std::max(1,radius/2);
        for(int dy=-fill;dy<=fill;++dy) for(int dx=-fill;dx<=fill;++dx) {
            int x=tx+dx,y=ty+dy;
            if(x<0||y<0||x>=w||y>=h) continue;
            int i=y*w+x;
            if(known[i]) continue;
            int d=(cy+dy)*w+cx+dx;
            output[i]=source[d]; known[i]=1; donor[i]=d;
            visitNeighbors(i,[&](int j){if(!known[j]&&!queued[j]){queue.push_back(j);queued[j]=1;}});
        }
    }
    if(std::find(known.begin(),known.end(),uint8_t(0))!=known.end()) throw std::runtime_error("Incomplete repair");
    return output;
}
}
