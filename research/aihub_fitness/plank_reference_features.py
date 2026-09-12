"""§39 직접 정렬의 Python 대조식. 휴대폰에서 회수한 좌표에 Kotlin과 같은 관측 조건을 적용한다."""
import math

def features(f):
    xy,v,w,h=f['xy'],f['visibility'],f['w'],f['h']
    def visible(i,cut=.5):
        return math.isfinite(v[i]) and v[i]>=cut and all(math.isfinite(xy[2*i+j]) and 0<=xy[2*i+j]<=1 for j in (0,1))
    def p(i):return (xy[i*2]*w,xy[i*2+1]*h)
    sides=[s for s in [(7,11,23,25,27),(8,12,24,26,28)] if all(visible(i) for i in s[1:])]
    if not sides:return {}
    side=max(sides,key=lambda s:min(v[i] for i in s[1:]))
    e,s,hp,k,a=map(p,side);n=p(0);torso=math.dist(s,hp);length=math.dist(s,a)
    if torso<20 or length<torso*1.6 or length<min(w,h)*.25 or not all(visible(i,.2) for i in (11,12)):return {}
    ka=(hp[0]-k[0],hp[1]-k[1]);kb=(a[0]-k[0],a[1]-k[1])
    knee=math.degrees(math.acos(max(-1,min(1,sum(x*y for x,y in zip(ka,kb))/max(math.hypot(*ka)*math.hypot(*kb),1e-6)))))
    if length/max(math.dist(p(11),p(12)),1)<8 or abs(a[0]-s[0])/length<.7 or knee<145:return {}
    def up(ux,uy):
        nx,ny=-uy,ux
        return (-nx,-ny) if ny>0 else (nx,ny)
    nx,ny=up((a[0]-s[0])/length,(a[1]-s[1])/length)
    result={'plank_hip_offset':((hp[0]-s[0])*nx+(hp[1]-s[1])*ny)/length}
    if visible(side[0]) and visible(0) and torso*.03<=math.dist(e,n)<=torso*.65:
        ux,uy=(s[0]-hp[0])/torso,(s[1]-hp[1])/torso;nx,ny=up(ux,uy)
        fx,fy=n[0]-e[0],n[1]-e[1];ex,ey=e[0]-s[0],e[1]-s[1]
        result['plank_head_pitch']=math.degrees(math.atan2(fx*ux+fy*uy,-fx*nx-fy*ny))
        if math.dist(e,s)>=torso*.08:result['plank_neck_pitch']=math.degrees(math.atan2(ex*nx+ey*ny,ex*ux+ey*uy))
    return result
