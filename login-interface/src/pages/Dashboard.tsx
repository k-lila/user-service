import { Navbar } from '../components/NavBar'
import { ProfileBox } from '../components/ProfileBox'

export const Dashboard = () => {
  return (
    <>
      <Navbar />
      <div className="min-h-screen flex items-center justify-center bg-gray-100">
        <ProfileBox />
      </div>
    </>
  )
}
