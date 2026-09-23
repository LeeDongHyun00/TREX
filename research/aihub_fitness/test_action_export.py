"""미동의/누락/가짜 시간 입력이 학습 자료로 승격되지 않는지 검사한다."""
import unittest
import numpy as np
from export_action_shadow import extract


class ActionExportTest(unittest.TestCase):
    def source(self):
        xy=np.array([[.5,.1+i*.02] for i in range(33)],np.float32).ravel().tolist()
        frames=[dict(t_ms=t,epoch=epoch,width=640,height=480,xy=xy,visibility=[1]*33,presence=[1]*33)
                for t,epoch in [(0,1),(150,1),(400,2)]]
        return {"action":{"schema":"trex.action.shadow/1","capture_enabled":True,
                          "raw_schema":"trex.pose.packet/2","raw_frames":frames}}
    def test_real_times_and_epochs_survive(self):
        x,t,e=extract(self.source())
        self.assertEqual((3,132),x.shape); self.assertEqual([0,150,400],t.tolist()); self.assertEqual([1,1,2],e.tolist())
    def test_opt_in_required(self):
        x=self.source(); x["action"]["capture_enabled"]=False
        with self.assertRaises(ValueError): extract(x)
    def test_legacy_cannot_be_reconstructed(self):
        with self.assertRaises(ValueError): extract({"motion":{"frames":[]}})
    def test_dropped_data_rejected(self):
        for key in ("raw_dropped","busy_dropped"):
            x=self.source(); x["action"][key]=1
            with self.assertRaises(ValueError): extract(x)
    def test_reordered_time_rejected(self):
        x=self.source(); x["action"]["raw_frames"][1]["t_ms"]=0
        with self.assertRaises(ValueError): extract(x)


if __name__=="__main__": unittest.main()
