import glob,csv,collections,os
c=collections.Counter(); reps=collections.Counter(); per={}
base='C:/Users/hp276/Desktop/trex/data/mm-fit/mm-fit'
for f in sorted(glob.glob(base+'/w*/w*_labels.csv')):
    w=os.path.basename(os.path.dirname(f))
    for row in csv.reader(open(f)):
        if len(row)>=4:
            ex=row[3].strip(); c[ex]+=1
            try: reps[ex]+=int(row[2])
            except: pass
            if ex=='situps':
                per.setdefault(w,[]).append((int(row[0]),int(row[1]),int(row[2])))
print(c); print('situps reps',reps['situps'], 'workouts', len(per))
for k,v in per.items(): print(k, v)
