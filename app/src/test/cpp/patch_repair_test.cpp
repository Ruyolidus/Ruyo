#include "patch_repair.h"
#include <cassert>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <string>
#include <random>
static int32_t rgb(int r,int g,int b){return int32_t(0xff000000u | r<<16 | g<<8 | b);}
static void ppm(std::string path,const std::vector<int32_t>& p,int w,int h){std::ofstream f(path,std::ios::binary);f<<"P6\n"<<w<<" "<<h<<"\n255\n";for(auto c:p)for(int s:{16,8,0})f.put((c>>s)&255);}
int main(int argc,char**argv){
    const int w=240,h=180; std::vector<int32_t> original(w*h); std::vector<uint8_t> mask(w*h),excluded(w*h);
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
        double edge=78+26*std::sin(x*.035); bool bright=y<edge;
        int grain=int(8*std::sin(x*.55)*std::cos(y*.4));
        original[y*w+x]=bright?rgb(220+grain,120+grain,30+grain):rgb(35+grain,45+grain,65+grain);
        // Thin strokes cross the curved edge; the wider center must reconstruct texture as well.
        mask[y*w+x]=(y>=35&&y<130&&((x>=76&&x<82)||(x>=151&&x<159))) || (x>=80&&x<153&&y>=83&&y<89);
    }
    auto damaged=original; for(size_t i=0;i<mask.size();i++)if(mask[i])damaged[i]=rgb(255,255,255);
    const auto start=std::chrono::steady_clock::now();
    auto result=ruyo::repair(damaged,w,h,mask,excluded);
    double error=0; int count=0,edgeWrong=0;
    for(size_t i=0;i<mask.size();i++){
        if(!mask[i])assert(result[i]==original[i]);
        else {for(int s:{16,8,0})error+=std::abs(int((uint32_t(result[i])>>s)&255)-int((uint32_t(original[i])>>s)&255));count+=3;
            if((((uint32_t(result[i])>>16)&255)>125) != (((uint32_t(original[i])>>16)&255)>125))edgeWrong++;
        }
    }
    std::cout<<"Curve MAE: "<<error/count<<"; edge errors: "<<edgeWrong<<"; ms: "<<std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count()<<"\n";
    assert(error/count<20); assert(edgeWrong<100);
    assert(result==ruyo::repair(damaged,w,h,mask,excluded));
    if(argc>1){std::string base=argv[1];ppm(base+"/repair-original.ppm",original,w,h);ppm(base+"/repair-damaged.ppm",damaged,w,h);ppm(base+"/repair-result.ppm",result,w,h);}
    // No-op, invalid sizes, full mask and border-touching holes.
    assert(ruyo::repair(original,w,h,std::vector<uint8_t>(w*h),excluded)==original);
    bool caught=false;try{ruyo::repair(original,w,h,std::vector<uint8_t>(w*h,1),excluded);}catch(const std::exception&){caught=true;}assert(caught);
    caught=false;try{ruyo::repair(original,-1,h,mask,excluded);}catch(const std::exception&){caught=true;}assert(caught);
    std::mt19937 rng(1337);
    for(int k=0;k<60;k++){
        int a=8+rng()%60,b=8+rng()%60;std::vector<int32_t> src(a*b,rgb(40+k,80,120));std::vector<uint8_t> holes(a*b),blocked(a*b);
        for(auto&v:holes)v=rng()%9==0;
        auto repaired=ruyo::repair(src,a,b,holes,blocked);assert(repaired==src);
    }
    // Excluded source art cannot be sampled, even when it looks like a good patch.
    std::fill(original.begin(),original.end(),rgb(25,35,45));std::fill(mask.begin(),mask.end(),0);
    for(int y=20;y<160;y++)for(int x=20;x<220;x++)if(x<100){original[y*w+x]=rgb(255,0,255);excluded[y*w+x]=1;}else if(x>120&&x<128){mask[y*w+x]=1;original[y*w+x]=rgb(255,255,255);}
    result=ruyo::repair(original,w,h,mask,excluded);
    for(size_t i=0;i<mask.size();i++)if(mask[i])assert(result[i]==rgb(25,35,45));else assert(result[i]==original[i]);
    std::cout<<"Native repair checks passed\n";
}
