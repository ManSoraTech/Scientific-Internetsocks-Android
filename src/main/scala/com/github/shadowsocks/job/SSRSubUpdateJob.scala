package com.github.shadowsocks.job

import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

import com.evernote.android.job.Job.{Params, Result}
import com.evernote.android.job.{Job, JobRequest}
import com.github.shadowsocks.utils.IOUtils
import com.github.shadowsocks.ShadowsocksApplication.app
import okhttp3._
import java.util.concurrent.TimeUnit
import java.io.IOException
import com.github.shadowsocks.database._
import com.github.shadowsocks.utils.CloseUtils._
import com.github.shadowsocks.utils._
import android.util.{Base64, Log}
import android.widget.Toast
import android.content.Context
import android.os.Looper
import com.github.shadowsocks.R

/**
  * @author Mygod
  */
object SSRSubUpdateJob {
  final val TAG = "SSRSubUpdateJob"

  def schedule() = new JobRequest.Builder(SSRSubUpdateJob.TAG)
    .setPeriodic(TimeUnit.DAYS.toMillis(1))
    .setRequirementsEnforced(true)
    .setRequiredNetworkType(JobRequest.NetworkType.CONNECTED)
    .setRequiresCharging(false)
    .setUpdateCurrent(true)
    .build().schedule()
}

class SSRSubUpdateJob() extends Job {
  override def onRunJob(params: Params): Result = {
    Looper.prepare()
    if (app.settings.getInt(Key.ssrsub_autoupdate, 0) == 1) {
      app.ssrsubManager.getAllSSRSubs match {
        case Some(ssrsubs) =>
          var result = 1
          ssrsubs.foreach((ssrsub: SSRSub) => {

              var delete_profiles = app.profileManager.getAllProfilesByGroup(ssrsub.url_group) match {
                case Some(profiles) =>
                  profiles
                case _ => null
              }

              var ssrurl = "";
              var ssrauthuser = "";
              var ssrauthpwd = "";
              if(ssrsub.url.contains("@")){
                  var s_url = ssrsub.url.split("/");
                  val urlargs = s_url(2).split("@");
                  val authargs = urlargs(0).split(":");
                  ssrauthuser = authargs(0)
                  if(authargs.length>1) ssrauthpwd = authargs(1);
                  s_url(2)=urlargs(1);
                  ssrurl=s_url.mkString("/");
              }else{
                  ssrurl=ssrsub.url;
              }
           
              val auth = new Authenticator() {
                  def authenticate(route:Route, response:Response):Request = {
                       val credential = Credentials.basic(ssrauthuser, ssrauthpwd);
                       response.request().newBuilder()
                            .header("Authorization", credential)
                            .build();
                  }
              }

              val builder = new OkHttpClient.Builder()
                              .connectTimeout(5, TimeUnit.SECONDS)
                              .writeTimeout(5, TimeUnit.SECONDS)
                              .readTimeout(5, TimeUnit.SECONDS)
                              .authenticator(auth)

              val client = builder.build();

              val request = new Request.Builder()
                .url(ssrurl)
		.header("Authorization", Credentials.basic(ssrauthuser, ssrauthpwd))
                .build();

              try {
                val response = client.newCall(request).execute()
                val code = response.code()
                if (code == 200) {
                  val response_string = new String(Base64.decode(response.body().string, Base64.URL_SAFE))
                  var limit_num = -1
                  var encounter_num = 0
                  if (response_string.indexOf("MAX=") == 0) {
                    limit_num = response_string.split("\\n")(0).split("MAX=")(1).replaceAll("\\D+","").toInt
                  }
                  var profiles_ssr = Parser.findAll_ssr(response_string)

                  val profiles_temp = Parser.findAll_ssr(response_string)
                  val profiles_count = profiles_temp.length
                  if (limit_num != -1 && limit_num != profiles_count) {
                    profiles_ssr = scala.util.Random.shuffle(profiles_ssr)
                  }

                  profiles_ssr.foreach((profile: Profile) => {
                    if (encounter_num < limit_num && limit_num != -1 || limit_num == -1) {
                      val result_id = app.profileManager.createProfile_sub(profile)
                      if (result_id != 0) {
                        delete_profiles = delete_profiles.filter(_.id != result_id)
                      }
                    }
                    encounter_num += 1
                  })

                  delete_profiles.foreach((profile: Profile) => {
                    if (profile.id != app.profileId) {
                      app.profileManager.delProfile(profile.id)
                    }
                  })
                } else throw new Exception("error")
                response.body().close()
              } catch {
                case e: IOException => {
                  result = 0
                }
              }
          })
          if (result == 1) {
            Log.i(SSRSubUpdateJob.TAG, "update ssr sub successfully!")
            Toast.makeText(app, app.resources.getString(R.string.ssrsub_toast_success), Toast.LENGTH_SHORT).show
            Looper.loop()
            Result.SUCCESS
          } else {
            Log.i(SSRSubUpdateJob.TAG, "update ssr sub failed!")
            Toast.makeText(app, app.resources.getString(R.string.ssrsub_toast_fail), Toast.LENGTH_SHORT).show
            Looper.loop()
            Result.RESCHEDULE
          }
        case _ => {
          Log.i(SSRSubUpdateJob.TAG, "update ssr sub failed!")
          Toast.makeText(app, app.resources.getString(R.string.ssrsub_toast_fail), Toast.LENGTH_SHORT).show
          Looper.loop()
          Result.FAILURE
        }
      }
    } else {
      Result.RESCHEDULE
    }
  }
}

